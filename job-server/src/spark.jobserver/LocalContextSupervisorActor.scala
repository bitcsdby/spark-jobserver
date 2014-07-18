package spark.jobserver

import akka.actor.{ Props, ActorRef, PoisonPill }
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import ooyala.common.akka.InstrumentedActor
import spark.jobserver.io.JobDAO
import scala.collection.mutable
import scala.util.{Failure, Success, Try}
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import ooyala.common.akka.metrics.MetricsWrapper

/** Messages common to all ContextSupervisors */
object ContextSupervisor {
  // Messages/actions
  case object AddContextsFromConfig // Start up initial contexts
  case object ListContexts
  case class AddContext(name: String, contextConfig: Config)
  case class GetAdHocContext(classPath: String, contextConfig: Config)
  case class GetContext(name: String) // returns JobManager, JobResultActor
  case class GetResultActor(name: String)  // returns JobResultActor
  case class StopContext(name: String)

  // Errors/Responses
  case object ContextInitialized
  case class ContextInitError(t: Throwable)
  case object ContextAlreadyExists
  case object NoSuchContext
  case object ContextStopped
}

/**
 * This class starts and stops JobManagers / Contexts in-process.
 * It is responsible for watching out for the death of contexts/JobManagers.
 *
 * == Auto context start configuration ==
 * Contexts can be configured to be created automatically at job server initialization.
 * Configuration example:
 * {{{
 *   spark {
 *     contexts {
 *       olap-demo {
 *         num-cpu-cores = 4            # Number of cores to allocate.  Required.
 *         memory-per-node = 1024m      # Executor memory per node, -Xmx style eg 512m, 1G, etc.
 *       }
 *     }
 *   }
 * }}}
 *
 * == Other configuration ==
 * {{{
 *   spark {
 *     jobserver {
 *       context-creation-timeout = 15 s
 *     }
 *
 *     # Default settings for all context creation
 *     context-settings {
 *       spark.mesos.coarse = true
 *     }
 *   }
 * }}}
 */
class LocalContextSupervisorActor(dao: JobDAO) extends InstrumentedActor {
  import ContextSupervisor._
  import scala.collection.JavaConverters._
  import scala.concurrent.duration._

  val config = context.system.settings.config
  val defaultContextConfig = config.getConfig("spark.context-settings")
  val contextTimeout = config.getMilliseconds("spark.jobserver.context-creation-timeout").toInt / 1000
  import context.dispatcher   // to get ExecutionContext for futures

  private val contexts = mutable.HashMap.empty[String, ActorRef]
  private val resultActors = mutable.HashMap.empty[String, ActorRef]

  // This is for capturing results for ad-hoc jobs. Otherwise when ad-hoc job dies, resultActor also dies,
  // and there is no way to retrieve results.
  val globalResultActor = context.actorOf(Props[JobResultActor], "global-result-actor")

  // Context metrics
  val numOfContexts = MetricsWrapper.newCounter(getClass, "num-contexts")

  def wrappedReceive: Receive = {
    case AddContextsFromConfig =>
      addContextsFromConfig(config)

    case ListContexts =>
      sender ! contexts.keys.toSeq

    case AddContext(name, contextConfig) =>
      val originator = sender // Sender is a mutable reference, must capture in immutable val
      val mergedConfig = contextConfig.withFallback(defaultContextConfig)
      if (contexts contains name) {
        originator ! ContextAlreadyExists
      } else {
        startContext(name, mergedConfig, false, contextTimeout) { contextMgr =>
          originator ! ContextInitialized
        } { err =>
          originator ! ContextInitError(err)
        }
      }

    case GetAdHocContext(classPath, contextConfig) =>
      val originator = sender // Sender is a mutable reference, must capture in immutable val
      logger.info("Creating SparkContext for adhoc jobs.")

      val mergedConfig = contextConfig.withFallback(defaultContextConfig)

      // Keep generating context name till there is no collision
      var contextName = ""
      do {
        contextName = java.util.UUID.randomUUID().toString().substring(0, 8) + "-" + classPath
      } while (contexts contains contextName)

      // Create JobManagerActor and JobResultActor
      startContext(contextName, mergedConfig, true, contextTimeout) { contextMgr =>
        originator ! (contexts(contextName), resultActors(contextName))
      } { err =>
        originator ! ContextInitError(err)
      }

    case GetResultActor(name) =>
      sender ! resultActors.get(name).getOrElse(globalResultActor)

    case GetContext(name) =>
      if (contexts contains name) {
        sender ! (contexts(name), resultActors(name))
      } else {
        sender ! NoSuchContext
      }

    case StopContext(name) =>
      if (contexts contains name) {
        logger.info("Shutting down context {}", name)
        contexts(name) ! PoisonPill
        contexts.remove(name)
        resultActors.remove(name)
        if (numOfContexts.getCount > 0)
          numOfContexts.dec
        sender ! ContextStopped
      } else {
        sender ! NoSuchContext
      }
  }

  private def startContext(name: String, contextConfig: Config, isAdHoc: Boolean, timeoutSecs: Int = 1)
                          (successFunc: ActorRef => Unit)
                          (failureFunc: Throwable => Unit) {
    require(!(contexts contains name), "There is already a context named " + name)
    logger.info("Creating a SparkContext named {}", name)

    val resultActorRef = if (isAdHoc) Some(globalResultActor) else None
    val ref = context.actorOf(Props(
      classOf[JobManagerActor], dao, name, contextConfig, isAdHoc, resultActorRef), name)
    (ref ? JobManagerActor.Initialize)(Timeout(timeoutSecs.second)).onComplete {
      case Failure(e: Exception) =>
        logger.error("Exception after sending Initialize to JobManagerActor", e)
        // Make sure we try to shut down the context in case it gets created anyways
        ref ! PoisonPill
        failureFunc(e)
      case Success(JobManagerActor.Initialized(resultActor)) =>
        logger.info("SparkContext {} initialized", name)
        contexts(name) = ref
        resultActors(name) = resultActor
        numOfContexts.inc // Increase a metric
        successFunc(ref)
      case Success(JobManagerActor.InitError(t)) =>
        ref ! PoisonPill
        failureFunc(t)
      case x =>
        logger.warn("Unexpected message received by startContext: {}", x)
    }
  }

  // Adds the contexts from the config file
  private def addContextsFromConfig(config: Config) {
    for (contexts <- Try(config.getObject("spark.contexts"))) {
      contexts.keySet().asScala.foreach { contextName =>
        val contextConfig = config.getConfig("spark.contexts." + contextName)
          .withFallback(defaultContextConfig)
        startContext(contextName, contextConfig, false, contextTimeout) { ref => } {
          e => logger.error("Unable to start context " + contextName, e)
        }
        Thread sleep 500 // Give some spacing so multiple contexts can be created
      }
    }
  }
}
