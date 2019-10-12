package brt4cats

import cats.{ApplicativeError, FlatMap, Functor}
import cats.effect.{
  CancelToken,
  ConcurrentEffect,
  ExitCase,
  ExitCode,
  Fiber,
  IO,
  IOApp,
  SyncIO
}
import cats.implicits._
import cats.effect.implicits._
import scala.concurrent.duration._

final case class ReaderT[-R, F[+ _], +A](run: R => F[A]) {
  def flatMap[R1 <: R, B](f: A => ReaderT[R1, F, B])(
      implicit F: FlatMap[F]): ReaderT[R1, F, B] =
    ReaderT[R1, F, B](r1 => F.flatMap(run(r1))(a => f(a).run(r1)))

  def flatMapF[R1 <: R, B](f: A => F[B])(
      implicit F: FlatMap[F]): ReaderT[R1, F, B] =
    ReaderT[R1, F, B](r1 => F.flatMap(run(r1))(a => f(a)))

  def map[R1 <: R, B](f: A => B)(implicit F: Functor[F]): ReaderT[R1, F, B] =
    ReaderT[R1, F, B](r1 => F.map(run(r1))(a => f(a)))

  def as[R1 <: R, B](b: B)(implicit F: Functor[F]): ReaderT[R1, F, B] =
    ReaderT[R1, F, B](r1 => F.map(run(r1))(_ => b))

  def attempt(implicit AE: ApplicativeError[F, Throwable])
    : ReaderT[R, F, Either[Throwable, A]] = ReaderT(r => run(r).attempt)

  def >>[R1 <: R, B](that: ReaderT[R1, F, B])(
      implicit F: FlatMap[F]): ReaderT[R1, F, B] = flatMap(_ => that)
}

trait Runtime[R] {
  def getRuntime: R
}

object ReaderT {
  implicit def concurrentEffectReaderT[F[+ _], R](
      implicit
      CE: ConcurrentEffect[F],
      R: Runtime[R]
  ): ConcurrentEffect[ReaderT[R, F, ?]] =
    new ConcurrentEffect[ReaderT[R, F, ?]] {
      override def runCancelable[A](fa: ReaderT[R, F, A])(
          cb: Either[Throwable, A] => IO[Unit])
        : SyncIO[CancelToken[ReaderT[R, F, ?]]] =
        fa.run(R.getRuntime).runCancelable(cb).map(pure(_))

      override def runAsync[A](fa: ReaderT[R, F, A])(
          cb: Either[Throwable, A] => IO[Unit]): SyncIO[Unit] =
        fa.run(R.getRuntime).runAsync(cb)

      override def start[A](
          fa: ReaderT[R, F, A]): ReaderT[R, F, Fiber[ReaderT[R, F, ?], A]] =
        ReaderT { r: R =>
          fa.run(r).start.map { fiber =>
            Fiber(ReaderT[R, F, A](_ => fiber.join),
                  ReaderT[R, F, Unit](_ => fiber.cancel))
          }
        }

      override def racePair[A, B](rfa: ReaderT[R, F, A], rfb: ReaderT[R, F, B])
        : ReaderT[R,
                  F,
                  Either[(A, Fiber[ReaderT[R, F, ?], B]),
                         (Fiber[ReaderT[R, F, ?], A], B)]] = ReaderT { r =>
        rfa
          .run(r)
          .racePair(rfb.run(r))
          .map {
            case Left((a, fiber)) =>
              Left(
                (a,
                 Fiber(ReaderT[R, F, B](_ => fiber.join),
                       ReaderT[R, F, Unit](_ => fiber.cancel))))
            case Right((fiber, b)) =>
              Right(
                (Fiber(ReaderT[R, F, A](_ => fiber.join),
                       ReaderT[R, F, Unit](_ => fiber.cancel)),
                 b))
          }
      }

      override def async[A](
          k: (Either[Throwable, A] => Unit) => Unit): ReaderT[R, F, A] =
        ReaderT(_ => CE.async(k))

      override def asyncF[A](
          k: (Either[Throwable, A] => Unit) => ReaderT[R, F, Unit])
        : ReaderT[R, F, A] =
        ReaderT { r =>
          val k2: (Either[Throwable, A] => Unit) => F[Unit] = cb => k(cb).run(r)

          CE.asyncF(k2)
        }

      override def suspend[A](thunk: => ReaderT[R, F, A]): ReaderT[R, F, A] =
        ReaderT(r => thunk.run(r))

      override def bracketCase[A, B](acquire: ReaderT[R, F, A])(
          use: A => ReaderT[R, F, B])(
          release: (A, ExitCase[Throwable]) => ReaderT[R, F, Unit])
        : ReaderT[R, F, B] = ReaderT { r =>
        CE.bracketCase(acquire.run(r))(a => use(a).run(r))((a, exit) =>
          release(a, exit).run(r))
      }

      override def raiseError[A](e: Throwable): ReaderT[R, F, A] =
        ReaderT(_ => CE.raiseError(e))

      override def handleErrorWith[A](rfa: ReaderT[R, F, A])(
          f: Throwable => ReaderT[R, F, A]): ReaderT[R, F, A] =
        ReaderT { r =>
          CE.handleErrorWith(rfa.run(r)) { t =>
            f(t).run(r)
          }
        }

      override def pure[A](x: A): ReaderT[R, F, A] = ReaderT(_ => CE.pure(x))

      override def flatMap[A, B](fa: ReaderT[R, F, A])(
          f: A => ReaderT[R, F, B]): ReaderT[R, F, B] = fa.flatMap(f)

      override def tailRecM[A, B](a: A)(
          f: A => ReaderT[R, F, Either[A, B]]): ReaderT[R, F, B] =
        ReaderT { r =>
          val ff: A => F[Either[A, B]] = a => f(a).run(r)
          CE.tailRecM(a)(ff)
        }
    }

  def liftF[F[+ _], A](fa: F[A]): ReaderT[Any, F, A] = ReaderT(_ => fa)
}

trait Foo extends Serializable
trait Bar extends Serializable

object Main extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    val r1 = ReaderT[Foo, Option, Int] { r =>
      println("!!!", r)
      Some(1)
    }
    val r2 = ReaderT[Bar, Option, String] { r1 =>
      println("!!!", r1)
      Some("1")
    }

    val r3: ReaderT[Bar with Foo, Option, String] = r1.flatMap(_ => r2)

    implicit val r: Runtime[Any] = new Runtime[Any] {
      override def getRuntime: Any = ()
    }

    val a = for {
      f1 <- ReaderT
        .liftF[IO, Unit](timer.sleep(2.second) *> IO(println("!")))
        .start
      f2 <- ReaderT
        .liftF[IO, Unit](timer.sleep(2.second) *> IO(println("!!")))
        .start
      _ <- f1.join
      _ <- f2.join
    } yield ()

    a.run(()).as(ExitCode.Success)

//    IO(ExitCode.Success)
  }

  def foo[F[_]: ConcurrentEffect, A](a: F[A], b: F[A]): F[Either[A, A]] = {
    a.race(b)
  }
}
