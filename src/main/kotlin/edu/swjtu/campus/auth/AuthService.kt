package edu.swjtu.campus.auth

import edu.swjtu.campus.data.Store
import edu.swjtu.campus.model.Role
import edu.swjtu.campus.model.User
import edu.swjtu.campus.model.text
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import java.util.concurrent.ConcurrentHashMap

/** 业务异常，会被 StatusPages 统一转成 JSON 错误响应 */
class ApiException(val status: HttpStatusCode, override val message: String) : RuntimeException(message)

/**
 * 简易的会话式鉴权。
 * 真实项目中这里应替换为学校统一身份认证（CAS / OAuth）返回的票据校验。
 * 本地运行时用「用户名 + 密码」登录，成功后签发一个内存中的 token。
 */
class AuthService(private val store: Store) {

    private val sessions = ConcurrentHashMap<String, String>() // token -> userId

    fun login(username: String, password: String): Pair<String, User> {
        val user = store.users.byUsername(username)
            ?: throw ApiException(HttpStatusCode.Unauthorized, "用户不存在，请检查学号/工号")
        if (user.password != password) {
            throw ApiException(HttpStatusCode.Unauthorized, "密码错误")
        }
        val token = java.util.UUID.randomUUID().toString()
        sessions[token] = user.id
        return token to user
    }

    fun logout(token: String) {
        sessions.remove(token)
    }

    fun userOfToken(token: String?): User? {
        val id = token?.let { sessions[it] } ?: return null
        return store.users.byId(id)
    }
}

private fun ApplicationCall.token(): String? =
    request.headers[HttpHeaders.Authorization]
        ?.removePrefix("Bearer")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/** 要求已登录 */
fun ApplicationCall.requireUser(auth: AuthService): User =
    auth.userOfToken(token()) ?: throw ApiException(HttpStatusCode.Unauthorized, "请先登录")

/** 要求已登录且角色匹配 */
fun ApplicationCall.requireRole(auth: AuthService, vararg roles: Role): User {
    val user = requireUser(auth)
    if (user.role !in roles) {
        throw ApiException(
            HttpStatusCode.Forbidden,
            "当前身份为「${user.role.text}」，无权执行该操作"
        )
    }
    return user
}

/** 未登录时返回 null，不抛异常 */
fun ApplicationCall.currentUserOrNull(auth: AuthService): User? = auth.userOfToken(token())
