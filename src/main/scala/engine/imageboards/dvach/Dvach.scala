package engine.imageboards.dvach

import akka.http.scaladsl.HttpExt
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import engine.entities.{Board, File, Post, Thread}
import engine.imageboards.abstractimageboard.AbstractImageBoard
import engine.imageboards.abstractimageboard.AbstractImageBoardStructs.{Captcha, FetchPostsResponse, FormatPostRequest, FormatPostResponse}
import engine.imageboards.dvach.DvachImplicits._
import engine.imageboards.dvach.DvachStructs._
import engine.utils.{Extracted, RegExpRule}
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

class Dvach(implicit executionContext: ExecutionContext, materializer: ActorMaterializer, client: HttpExt) extends AbstractImageBoard {
  override val id: Int = 0
  override val name: String = "Двач"
  override val baseURL: String = "https://2ch.hk"
  override val captcha: Option[Captcha] = Some(
    Captcha(
      kind = "reCAPTCHA v2",
      key = "6LdwXD4UAAAAAHxyTiwSMuge1-pf1ZiEL4qva_xu"
    )
  )
  override val maxImages: Int = 4
  override val logo: String = "https://2ch.hk/newtest/resources/images/dvlogo.png"
  override val highlight: String = "#F26722"
  override val clipboardRegExps: List[String] = List("/салямчик двачик/")

  override val boards: List[Board] = Await.result(this.fetchBoards(), Duration.Inf)

  override val regExps: List[RegExpRule] = List(
    RegExpRule(
      openRegex = raw"""(<strong>)""".r,
      closeRegex = raw"""(<\/strong>)""".r,
      "bold"
    ),
    RegExpRule(
      openRegex = raw"""(<b>)""".r,
      closeRegex = raw"""(<\/b>)""".r,
      "bold"
    ),
    RegExpRule(
      openRegex = raw"""(<em>)""".r,
      closeRegex = raw"""(<\/em>)""".r,
      "italics"
    ),
    RegExpRule(
      openRegex = raw"""(<span class="unkfunc">)""".r,
      closeRegex = raw"""<span class="unkfunc">.*?(<\/span>)""".r,
      "quote"
    ),
    RegExpRule(
      openRegex = raw"""(<span class="spoiler">)""".r,
      closeRegex = raw"""<span class="spoiler">.*?(<\/span>)""".r,
      "spoiler"
    ),
    RegExpRule(
      openRegex = raw"""(<span class="s">)""".r,
      closeRegex = raw"""<span class="s">.*?(<\/span>)""".r,
      "strikethrough"
    ),
    RegExpRule(
      openRegex = raw"""(<span class="u">)""".r,
      closeRegex = raw"""<span class="u">.*?(<\/span>)""".r,
      "underline"
    ),
    RegExpRule(
      openRegex = raw"""(<a href="\/[\S]+\/.*" class="post-reply-link" data-thread="([\d]+)" data-num="([\d]+)">)""".r,
      closeRegex = raw"""<a href="\/[\S]+\/.*" class="post-reply-link" data-thread="[\d]+" data-num="[\d]+">.*(<\/a>)""".r,
      "reply"
    ),

  )

  println(s"[$name] Ready")

  override def fetchBoards(): Future[List[Board]] = {
    val response: Future[HttpResponse] = client
      .singleRequest(
        HttpRequest(
          uri = s"${this.baseURL}/makaba/mobile.fcgi?task=get_boards"
        )
      )

    response
      .flatMap(
        r =>
          Unmarshal(r)
            .to[String]
            .map(
              _
                .parseJson
                .convertTo[Map[String, List[DvachBoardsResponse]]]
            )
      )
      .map(
        _
          .values
          .flatten
          .map(
            board =>
              Board(id = board.id, name = board.name)
          )
          .toList
      )
  }

  override def fetchThreads(board: String): Future[List[Thread]] = {
    val response: Future[HttpResponse] = client
      .singleRequest(
        HttpRequest(
          uri = s"${this.baseURL}/$board/catalog.json"
        )
      )

    response
      .flatMap(
        r =>
          Unmarshal(r)
            .to[String]
            .map(
              _
                .parseJson
                .asJsObject
                .getFields("threads")
                .head
                .convertTo[List[DvachThreadsResponse]]
            )
      )
      .map(
        _
          .map(
            thread => {
              val extracted = this.fetchMarkups(thread.comment)
              val subject = this.fetchMarkups(thread.subject).content
              Thread(
                id = thread.num,
                subject = subject,
                content = extracted.content,
                postsCount = thread.`posts_count` + 1,
                timestampt = thread.timestamp,
                files = thread
                  .files
                  .map(
                    files =>
                      files
                        .map(
                          file =>
                            File(
                              name = file.fullname.getOrElse(file.displayname),
                              full = this.baseURL.concat(file.path),
                              thumbnail = this.baseURL.concat(file.thumbnail)
                            )
                        )
                  )
                  .getOrElse(List.empty),
                decorations = extracted.decorations,
                links = extracted.links,
                replies = extracted.replies
              )
            }
          )
      )
  }

  override def fetchPosts(board: String, thread: Int, since: Int): Future[FetchPostsResponse] = {
    val response: Future[HttpResponse] = client
      .singleRequest(
        HttpRequest(
          uri = s"${this.baseURL}/makaba/mobile.fcgi?task=get_thread&board=$board&thread=$thread&post=${since + 1}"
        )
      )

    response
      .flatMap(
        r =>
          Unmarshal(r)
            .to[String]
            .map(
              _
                .parseJson
                .convertTo[List[DvachPostsResponse]]
            )
      )
      .map(
        posts => {
          val formattedPosts = posts
            .map(
              post => {
                val extracted: Extracted = this.fetchMarkups(post.comment)
                Post(
                  id = post.num,
                  content = extracted.content,
                  timestamp = post.timestamp,
                  files = post
                    .files
                    .map(
                      files =>
                        files
                          .map(
                            file =>
                              File(
                                name = file.fullname.getOrElse(file.displayname),
                                full = this.baseURL.concat(file.path),
                                thumbnail = this.baseURL.concat(file.thumbnail)
                              )
                          )
                    )
                    .getOrElse(List.empty),
                  decorations = extracted.decorations,
                  links = extracted.links,
                  replies = extracted.replies,
                  selfReplies = List.empty
                )
              }
            )
          val originalPost: Post = formattedPosts.head
          val subject = posts.head.subject
          FetchPostsResponse(
            thread = Thread(
              id = originalPost.id,
              subject = subject
                .map(
                  s =>
                    this.fetchMarkups(s).content
                )
                .getOrElse(originalPost.content),
              content = originalPost.content,
              postsCount = formattedPosts.length + 1,
              timestampt = originalPost.timestamp,
              files = originalPost.files,
              decorations = originalPost.decorations,
              links = originalPost.links,
              replies = originalPost.replies,
            ),
            posts = formattedPosts
              .map(
                post =>
                  Post(
                    id = post.id,
                    content = post.content,
                    timestamp = post.timestamp,
                    files = post.files,
                    decorations = post.decorations,
                    links = post.links,
                    replies = post.replies,
                    selfReplies = this.fetchSelfReplies(post.id, formattedPosts)
                  )
              )
          )
        }
      )
  }

  override def formatPost(post: FormatPostRequest): FormatPostResponse = {
    FormatPostResponse(
      url = s"${this.baseURL}/makaba/posting.fcgi",
      data = DvachFormatPostData(
        board = post.board,
        thread = post.thread,
        subject = post.text,
        comment = post.text,
        images = List.tabulate[String](post.images)(x => s"image${x + 1}"),
        `captcha-key` = this.captcha.get.key,
        `g-recaptcha-response` = post.captcha
      ).toJson
    )
  }
}