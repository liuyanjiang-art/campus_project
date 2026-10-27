package edu.swjtu.campus.data

import edu.swjtu.campus.model.Activity
import edu.swjtu.campus.model.ActivityStatus
import edu.swjtu.campus.model.Registration
import edu.swjtu.campus.model.RegistrationStatus
import edu.swjtu.campus.model.Reminder
import edu.swjtu.campus.model.Role
import edu.swjtu.campus.model.User

/** 仓储基类：负责行 <-> 对象的转换以及通用 CRUD */
abstract class BaseRepo<T>(protected val table: CsvTable) {

    protected abstract fun fromRow(row: Map<String, String>): T

    protected abstract fun toRow(value: T): MutableMap<String, String>

    fun all(): List<T> = table.read { rows -> rows.map(::fromRow) }

    fun byId(id: String): T? = table.read { rows -> rows.firstOrNull { it["id"] == id }?.let(::fromRow) }

    fun find(predicate: (T) -> Boolean): T? = all().firstOrNull(predicate)

    fun filter(predicate: (T) -> Boolean): List<T> = all().filter(predicate)

    fun count(predicate: (T) -> Boolean): Int = filter(predicate).size

    fun insert(value: T) {
        table.tx { rows -> rows.add(toRow(value)) }
    }

    fun upsert(value: T) {
        table.tx { rows ->
            val row = toRow(value)
            val index = rows.indexOfFirst { it["id"] == row["id"] }
            if (index >= 0) rows[index] = row else rows.add(row)
        }
    }

    fun deleteById(id: String) {
        table.tx { rows -> rows.removeAll { it["id"] == id } }
    }

    fun deleteWhere(predicate: (T) -> Boolean) {
        table.tx { rows -> rows.removeAll { predicate(fromRow(it)) } }
    }
}

class UserRepo(dir: java.io.File) : BaseRepo<User>(
    CsvTable(
        java.io.File(dir, "users.csv"),
        listOf("id", "username", "password", "name", "role", "clubName", "college", "phone", "avatarUrl")
    )
) {
    override fun fromRow(row: Map<String, String>) = User(
        id = row["id"] ?: "",
        username = row["username"] ?: "",
        password = row["password"] ?: "",
        name = row["name"] ?: "",
        role = runCatching { Role.valueOf(row["role"] ?: "") }.getOrDefault(Role.STUDENT),
        clubName = row["clubName"] ?: "",
        college = row["college"] ?: "",
        phone = row["phone"] ?: "",
        avatarUrl = row["avatarUrl"] ?: ""
    )

    override fun toRow(value: User) = mutableMapOf(
        "id" to value.id,
        "username" to value.username,
        "password" to value.password,
        "name" to value.name,
        "role" to value.role.name,
        "clubName" to value.clubName,
        "college" to value.college,
        "phone" to value.phone,
        "avatarUrl" to value.avatarUrl
    )

    fun byUsername(username: String): User? =
        find { it.username.equals(username.trim(), ignoreCase = true) }
}

class ActivityRepo(dir: java.io.File) : BaseRepo<Activity>(
    CsvTable(
        java.io.File(dir, "activities.csv"),
        listOf(
            "id", "title", "clubName", "organizerId", "organizerName", "content", "location",
            "requirements", "capacity", "registrationDeadline", "startTime", "endTime",
            "status", "qrToken", "createdAt", "reviewComment", "reviewerName", "reviewedAt", "imageUrl"
        )
    )
) {
    override fun fromRow(row: Map<String, String>) = Activity(
        id = row["id"] ?: "",
        title = row["title"] ?: "",
        clubName = row["clubName"] ?: "",
        organizerId = row["organizerId"] ?: "",
        organizerName = row["organizerName"] ?: "",
        content = row["content"] ?: "",
        location = row["location"] ?: "",
        requirements = row["requirements"] ?: "",
        capacity = row["capacity"]?.toIntOrNull() ?: 0,
        registrationDeadline = row["registrationDeadline"] ?: "",
        startTime = row["startTime"] ?: "",
        endTime = row["endTime"] ?: "",
        status = runCatching { ActivityStatus.valueOf(row["status"] ?: "") }
            .getOrDefault(ActivityStatus.PENDING_REVIEW),
        qrToken = row["qrToken"] ?: "",
        createdAt = row["createdAt"] ?: "",
        reviewComment = row["reviewComment"] ?: "",
        reviewerName = row["reviewerName"] ?: "",
        reviewedAt = row["reviewedAt"] ?: "",
        imageUrl = row["imageUrl"] ?: ""
    )

    override fun toRow(value: Activity) = mutableMapOf(
        "id" to value.id,
        "title" to value.title,
        "clubName" to value.clubName,
        "organizerId" to value.organizerId,
        "organizerName" to value.organizerName,
        "content" to value.content,
        "location" to value.location,
        "requirements" to value.requirements,
        "capacity" to value.capacity.toString(),
        "registrationDeadline" to value.registrationDeadline,
        "startTime" to value.startTime,
        "endTime" to value.endTime,
        "status" to value.status.name,
        "qrToken" to value.qrToken,
        "createdAt" to value.createdAt,
        "reviewComment" to value.reviewComment,
        "reviewerName" to value.reviewerName,
        "reviewedAt" to value.reviewedAt,
        "imageUrl" to value.imageUrl
    )

    fun byQrToken(token: String): Activity? =
        find { it.qrToken.equals(token.trim(), ignoreCase = true) }
}

class RegistrationRepo(dir: java.io.File) : BaseRepo<Registration>(
    CsvTable(
        java.io.File(dir, "registrations.csv"),
        listOf("id", "activityId", "studentId", "studentName", "studentNo", "registeredAt", "status")
    )
) {
    override fun fromRow(row: Map<String, String>) = Registration(
        id = row["id"] ?: "",
        activityId = row["activityId"] ?: "",
        studentId = row["studentId"] ?: "",
        studentName = row["studentName"] ?: "",
        studentNo = row["studentNo"] ?: "",
        registeredAt = row["registeredAt"] ?: "",
        status = runCatching { RegistrationStatus.valueOf(row["status"] ?: "") }
            .getOrDefault(RegistrationStatus.REGISTERED)
    )

    override fun toRow(value: Registration) = mutableMapOf(
        "id" to value.id,
        "activityId" to value.activityId,
        "studentId" to value.studentId,
        "studentName" to value.studentName,
        "studentNo" to value.studentNo,
        "registeredAt" to value.registeredAt,
        "status" to value.status.name
    )
}

class ReminderRepo(dir: java.io.File) : BaseRepo<Reminder>(
    CsvTable(
        java.io.File(dir, "reminders.csv"),
        listOf("id", "studentId", "activityId", "activityTitle", "remindAt", "note", "enabled")
    )
) {
    override fun fromRow(row: Map<String, String>) = Reminder(
        id = row["id"] ?: "",
        studentId = row["studentId"] ?: "",
        activityId = row["activityId"] ?: "",
        activityTitle = row["activityTitle"] ?: "",
        remindAt = row["remindAt"] ?: "",
        note = row["note"] ?: "",
        enabled = row["enabled"] == "true"
    )

    override fun toRow(value: Reminder) = mutableMapOf(
        "id" to value.id,
        "studentId" to value.studentId,
        "activityId" to value.activityId,
        "activityTitle" to value.activityTitle,
        "remindAt" to value.remindAt,
        "note" to value.note,
        "enabled" to value.enabled.toString()
    )
}
