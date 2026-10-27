package edu.swjtu.campus

import edu.swjtu.campus.auth.ApiException
import edu.swjtu.campus.auth.AuthService
import edu.swjtu.campus.data.Store
import edu.swjtu.campus.model.ErrorResponse
import edu.swjtu.campus.routes.apiRoutes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.io.File

private val log = LoggerFactory.getLogger("CampusApp")

val AppJson: Json = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("HOST") ?: "0.0.0.0"
    log.info("校园社团活动发布与报名 App 启动中，访问 http://localhost:$port")
    embeddedServer(Netty, port = port, host = host, module = Application::module).start(wait = true)
}

fun Application.module() {
    val dataDir = File(System.getProperty("app.dataDir") ?: "data")
    val store = Store(dataDir)
    store.seedIfEmpty()
    val uploadDir = File(dataDir, "uploads").apply { mkdirs() }
    log.info("本地数据目录：{}（首次启动已写入示例数据）", dataDir.absolutePath)

    val auth = AuthService(store)

    install(ContentNegotiation) {
        json(AppJson)
    }

    install(DefaultHeaders)

    install(CallLogging) {
        level = Level.INFO
    }

    install(CORS) {
        // 前端部署在 Cloudflare Pages（production 与 preview 域名可能不同），
        // 因此放开全部来源；如需收紧，可改用
        // allowHost("your-app.pages.dev", schemes = listOf("https"))
        anyHost()
        // 允许前端发送的请求头：Authorization（Bearer 鉴权）、Content-Type（JSON / multipart）
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.Accept)
        // 允许的请求方法，显式包含跨域预检所用的 OPTIONS
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        // application/json 属「非简单内容类型」，会触发预检，需显式放行
        allowNonSimpleContentTypes = true
    }

    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorResponse(cause.message))
        }
        exception<Throwable> { call, cause ->
            log.error("请求处理异常", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(cause.message ?: "服务器内部错误")
            )
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse("接口或资源不存在"))
        }
    }

    routing {
        // 前端单页应用（HTML / CSS / JS / 图片）
        staticResources("/", "static") {
            default("index.html")
        }
        // 用户上传的本地图片（头像 / 活动封面）
        route("/uploads") {
            get("/{name}") {
                val name = call.parameters["name"].orEmpty().substringAfterLast('/')
                val file = File(uploadDir, name)
                if (name.isBlank() || !file.isFile) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("图片不存在"))
                } else {
                    call.respondFile(file)
                }
            }
        }
        // 后端 REST 接口
        route("/api") {
            apiRoutes(store, auth, uploadDir)
        }
    }
}
