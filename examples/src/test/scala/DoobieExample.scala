import cats.data.NonEmptyList
import cats.instances.list._
import cats.syntax.cartesian._
import cats.syntax.traverse._
import doobie.imports.{Query => _, _}
import doobie.h2.h2transactor._
import fs2.Task
import fs2.interop.cats._
import org.scalatest.{AsyncWordSpec, Matchers}

import scala.concurrent.{ExecutionContext, Future}

import fetch._
import fetch.implicits._

class DoobieExample extends AsyncWordSpec with Matchers {
  implicit override def executionContext = ExecutionContext.Implicits.global

  val createTransactor: Task[Transactor[Task]] =
    H2Transactor[Task]("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "sa", "")

  case class AuthorId(id: Int)
  case class Author(id: Int, name: String)

  val dropTable = sql"DROP TABLE IF EXISTS author".update.run

  val createTable = sql"""
     CREATE TABLE author (
       id INTEGER PRIMARY KEY,
       name VARCHAR(20) NOT NULL UNIQUE
     )
    """.update.run

  def addAuthor(author: Author) =
    sql"INSERT INTO author (id, name) VALUES(${author.id}, ${author.name})".update.run

  val authors: List[Author] =
    List("William Shakespeare", "Charles Dickens", "George Orwell").zipWithIndex.map {
      case (name, id) => Author(id + 1, name)
    }

  val xa: Transactor[Task] = (for {
    xa <- createTransactor
    _  <- (dropTable *> createTable *> authors.traverse(addAuthor)).transact(xa)
  } yield xa).unsafeRunSync.toOption.getOrElse(
    throw new Exception("Could not create test database and/or transactor")
  )

  implicit val authorDS = new DataSource[AuthorId, Author] {
    override def name = "AuthorDoobie"
    override def fetchOne(id: AuthorId): Query[Option[Author]] =
      Query.async { (ok, fail) =>
        fetchById(id).transact(xa).unsafeRunAsync(_.fold(fail, ok))
      }
    override def fetchMany(ids: NonEmptyList[AuthorId]): Query[Map[AuthorId, Author]] =
      Query.async { (ok, fail) =>
        fetchByIds(ids).map { authors =>
          authors.map(a => AuthorId(a.id) -> a).toMap
        }.transact(xa).unsafeRunAsync(_.fold(fail, ok))
      }

    def fetchById(id: AuthorId): ConnectionIO[Option[Author]] =
      sql"SELECT * FROM author WHERE id = $id".query[Author].option

    def fetchByIds(ids: NonEmptyList[AuthorId]): ConnectionIO[List[Author]] = {
      val q = fr"SELECT * FROM author WHERE" ++ Fragments.in(fr"id", ids)
      q.query[Author].list
    }

    implicit val authorIdMeta: Meta[AuthorId] =
      Meta[Int].xmap(AuthorId(_), _.id)
  }

  def author(id: Int): Fetch[Author] = Fetch(AuthorId(id))

  "We can fetch one author from the DB" in {
    val fetch: Fetch[Author]            = author(1)
    val fut: Future[(FetchEnv, Author)] = Fetch.runFetch[Future](fetch)
    fut.map {
      case (env, res) =>
        res shouldEqual Author(1, "William Shakespeare")
        env.rounds.size shouldEqual 1
    }
  }

  "We can fetch multiple authors from the DB in parallel" in {
    val fetch: Fetch[List[Author]]            = List(1, 2).traverse(author)
    val fut: Future[(FetchEnv, List[Author])] = Fetch.runFetch[Future](fetch)
    fut.map {
      case (env, res) =>
        res shouldEqual Author(1, "William Shakespeare") :: Author(2, "Charles Dickens") :: Nil
        env.rounds.size shouldEqual 1
    }
  }

  "We can fetch multiple authors from the DB using a for comprehension" in {
    val fetch: Fetch[List[Author]] = for {
      a <- author(1)
      b <- author(a.id + 1)
    } yield List(a, b)
    val fut: Future[(FetchEnv, List[Author])] = Fetch.runFetch[Future](fetch)
    fut.map {
      case (env, res) =>
        res shouldEqual Author(1, "William Shakespeare") :: Author(2, "Charles Dickens") :: Nil
        env.rounds.size shouldEqual 2
    }
  }

}
