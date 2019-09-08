package zio.interop

import java.util.concurrent.atomic.AtomicInteger

import com.twitter.util.{ Await, Future, Promise }
import zio.interop.twitter._
import zio.Task
import zio.ZIO
import zio.test._
import zio.test.Assertion._
import zio.Runtime
import scala.util.Try
object TwitterSpec
    extends DefaultRunnableSpec(
      suite("TwitterSpec")(
        suite("Task.fromTwitterFuture")(
          testM("return failing `Task` if future failed.") {
            val error                              = new Exception
            val future                             = Task(Future.exception[Int](error))
            val task                               = Task.fromTwitterFuture(future).unit
            val assertion: Assertion[Either[Throwable, Unit]] = equalTo(Left(error))
            assertM(task.either, assertion)
          },
          testM("return successful `Task` if future succeeded.") {
            val value  = 10
            val future = Task(Future.value(value))
            val task   = Task.fromTwitterFuture(future).option
            val assertion: Assertion[Option[Int]] = equalTo(Some(value))
            assertM(task, assertion)
          },
          testM("ensure future is interrupted together with task.") {
            val value = new AtomicInteger(0)
            val promise = new Promise[Unit] with Promise.InterruptHandler {
              override protected def onInterrupt(t: Throwable): Unit = setException(t)
            }
            val future = Task(promise.flatMap(_ => Future(value.incrementAndGet())))
            val task = 
              (for {
                fiber <- Task.fromTwitterFuture(future).fork
                _     <- fiber.interrupt
                _     <- Task.effect(promise.setDone())
                a     <- fiber.await
              } yield a).fold(_ => false, exit => exit.toEither.isLeft)
              
            task.map { b =>
              assert(b, isTrue) && assert(value.get(), equalTo(0))
            }
          }
        )
      ,
      suite("Runtime.unsafeRunToTwitterFuture")(
        test("return successful `Future` if Task evaluation succeeded.") {
          assert(Await.result(Runtime((), DefaultTestRunner.platform).unsafeRunToTwitterFuture(Task.succeed(2))), equalTo(2))
        },
        test("return failed `Future` if Task evaluation failed.") {
          val e    = new Throwable
          val task = Task.fail(e).unit
          val result = Try(Await.result(Runtime((), DefaultTestRunner.platform).unsafeRunToTwitterFuture(task))).toEither
          assert(result, isLeft(equalTo(e)))
        },
        test("ensure Task evaluation is interrupted together with Future.") {
          val value = new AtomicInteger(0)
          val ex = new Throwable
          val task: ZIO[Any, Throwable, Future[Int]] = for {
            promise <- zio.Promise.make[Throwable, Int]
            t       = promise.await.flatMap( _ =>               Task.effectTotal(value.incrementAndGet())       )
            future  = Runtime((), DefaultTestRunner.platform).unsafeRunToTwitterFuture(t)
            _       = future.raise(ex)
            _       <- promise.succeed(1)
          } yield future

          val result = Try(Await.result(Runtime((), DefaultTestRunner.platform).unsafeRun(task))).toEither
          assert(result, isLeft(isSubtype[InterruptedException](anything))) && assert(value.get, equalTo(0))
        }
      )
    )
  )
