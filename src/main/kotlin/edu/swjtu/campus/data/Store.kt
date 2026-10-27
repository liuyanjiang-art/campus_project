package edu.swjtu.campus.data

import edu.swjtu.campus.model.Activity
import edu.swjtu.campus.model.ActivityStatus
import edu.swjtu.campus.model.Registration
import edu.swjtu.campus.model.RegistrationStatus
import edu.swjtu.campus.model.Role
import edu.swjtu.campus.model.User
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 时间工具：全项目统一使用 "yyyy-MM-dd HH:mm" */
object TimeUtil {

    val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun now(): String = LocalDateTime.now().format(FMT)

    /** 兼容前端 datetime-local 传入的 "2026-10-22T14:30" 以及带秒的格式 */
    fun normalize(raw: String): String = raw.trim().replace('T', ' ').take(16)

    fun parse(raw: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(normalize(raw), FMT) }.getOrNull()

    fun plusDays(days: Long, hours: Long = 0): String =
        LocalDateTime.now().plusDays(days).plusHours(hours).format(FMT)
}

/** 简单的 ID 生成 */
object IdGen {
    fun next(prefix: String): String =
        prefix + "-" + java.util.UUID.randomUUID().toString().replace("-", "").take(8).uppercase()

    /** 二维码令牌：8 位大写字母数字 */
    fun qrToken(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
}

/** 全部仓储的集合，以及首次启动的种子数据 */
class Store(val dir: File) {

    val users = UserRepo(dir)
    val activities = ActivityRepo(dir)
    val registrations = RegistrationRepo(dir)
    val reminders = ReminderRepo(dir)

    init {
        dir.mkdirs()
    }

    fun seedIfEmpty() {
        if (users.all().isNotEmpty()) return

        // ---- 用户 ----
        val teacher = User(
            id = "U-T001", username = "teacher01", password = "123456", name = "王老师",
            role = Role.TEACHER, college = "校团委"
        )
        val guitar = User(
            id = "U-O001", username = "guitarclub", password = "123456", name = "李思远",
            role = Role.ORGANIZER, clubName = "吉他社", college = "人文学院"
        )
        val acm = User(
            id = "U-O002", username = "acmclub", password = "123456", name = "张一鸣",
            role = Role.ORGANIZER, clubName = "ACM竞赛社", college = "计算机与人工智能学院"
        )
        val basketball = User(
            id = "U-O003", username = "basketballclub", password = "123456", name = "王强",
            role = Role.ORGANIZER, clubName = "篮球社", college = "体育学院"
        )
        val literature = User(
            id = "U-O004", username = "litclub", password = "123456", name = "赵文博",
            role = Role.ORGANIZER, clubName = "文学社", college = "人文学院"
        )
        val s1 = User(
            id = "U-S001", username = "20210001", password = "123456", name = "陈晓明",
            role = Role.STUDENT, college = "计算机与人工智能学院"
        )
        val s2 = User(
            id = "U-S002", username = "20210002", password = "123456", name = "刘雨欣",
            role = Role.STUDENT, college = "外国语学院"
        )
        listOf(teacher, guitar, acm, basketball, literature, s1, s2).forEach(users::insert)

        // ---- 活动 ----
        val a1 = Activity(
            id = "A-1001",
            title = "吉他社迎新音乐会",
            clubName = "吉他社",
            organizerId = guitar.id,
            organizerName = guitar.name,
            content = "新学期第一场开放麦！吉他社邀请所有热爱音乐的同学一起度过一个下午。现场有民谣弹唱、指弹独奏、乐队合作等节目，也欢迎自带乐器上台即兴表演。",
            location = "犀浦校区 大学生活动中心 报告厅",
            requirements = "西南交通大学在校学生，无需乐理基础，喜爱音乐即可",
            capacity = 120,
            registrationDeadline = TimeUtil.plusDays(3),
            startTime = TimeUtil.plusDays(4, 4),
            endTime = TimeUtil.plusDays(4, 7),
            status = ActivityStatus.PUBLISHED,
            qrToken = "GTAR2026",
            createdAt = TimeUtil.now(),
            reviewerName = teacher.name,
            reviewedAt = TimeUtil.now(),
            imageUrl = "/images/music.svg"
        )
        val a2 = Activity(
            id = "A-1002",
            title = "校园十佳歌手大赛（初赛）",
            clubName = "吉他社",
            organizerId = guitar.id,
            organizerName = guitar.name,
            content = "一年一度的校园十佳歌手大赛正式启动！初赛为清唱 90 秒，评委现场打分，前 30 名进入复赛。",
            location = "犀浦校区 一教 A 座 音乐厅",
            requirements = "在校本科生、研究生均可报名，自备伴奏者需提前一天提交文件",
            capacity = 200,
            registrationDeadline = TimeUtil.plusDays(10),
            startTime = TimeUtil.plusDays(12, 3),
            endTime = TimeUtil.plusDays(12, 8),
            status = ActivityStatus.PENDING_REVIEW,
            qrToken = "SING2026",
            createdAt = TimeUtil.now(),
            imageUrl = "/images/music.svg"
        )
        val a3 = Activity(
            id = "A-1003",
            title = "ACM 新生程序设计竞赛",
            clubName = "ACM竞赛社",
            organizerId = acm.id,
            organizerName = acm.name,
            content = "面向大一新生的入门级算法竞赛，5 道题，3 小时，个人赛。赛后将组织题解分享会，帮助大家快速上手算法竞赛。",
            location = "犀浦校区 计算机实验中心 3 楼机房",
            requirements = "2026 级新生，掌握 C/C++ 或 Java 基础语法",
            capacity = 60,
            registrationDeadline = TimeUtil.plusDays(5),
            startTime = TimeUtil.plusDays(6, 5),
            endTime = TimeUtil.plusDays(6, 8),
            status = ActivityStatus.PUBLISHED,
            qrToken = "ACM2026X",
            createdAt = TimeUtil.now(),
            reviewerName = teacher.name,
            reviewedAt = TimeUtil.now(),
            imageUrl = "/images/code.svg"
        )
        val a4 = Activity(
            id = "A-1004",
            title = "篮球社新生友谊赛",
            clubName = "篮球社",
            organizerId = basketball.id,
            organizerName = basketball.name,
            content = "以学院为单位组队，5v5 全场对抗，采用小组循环 + 淘汰赛制。现场提供饮用水与基础医疗支持。",
            location = "犀浦校区 北区篮球场 2 号场地",
            requirements = "在校学生，需自备球衣与运动鞋，建议购买短期运动意外险",
            capacity = 40,
            registrationDeadline = TimeUtil.plusDays(2),
            startTime = TimeUtil.plusDays(3, 6),
            endTime = TimeUtil.plusDays(3, 9),
            status = ActivityStatus.PUBLISHED,
            qrToken = "BASK2026",
            createdAt = TimeUtil.now(),
            reviewerName = teacher.name,
            reviewedAt = TimeUtil.now(),
            imageUrl = "/images/basketball.svg"
        )
        val a5 = Activity(
            id = "A-1005",
            title = "文学社读书分享会：《平凡的世界》",
            clubName = "文学社",
            organizerId = literature.id,
            organizerName = literature.name,
            content = "本期共读路遥《平凡的世界》，由三位社员做主题分享，随后自由讨论。现场提供茶点，参与者可自带书摘。",
            location = "犀浦校区 图书馆 4 楼 研讨室",
            requirements = "在校学生，建议提前阅读全书或前三章",
            capacity = 30,
            registrationDeadline = TimeUtil.plusDays(1),
            startTime = TimeUtil.plusDays(2, 2),
            endTime = TimeUtil.plusDays(2, 4),
            status = ActivityStatus.PUBLISHED,
            qrToken = "BOOK2026",
            createdAt = TimeUtil.now(),
            reviewerName = teacher.name,
            reviewedAt = TimeUtil.now(),
            imageUrl = "/images/reading.svg"
        )
        val a6 = Activity(
            id = "A-1006",
            title = "摄影社校园采风（需补充安全预案）",
            clubName = "摄影社",
            organizerId = guitar.id,
            organizerName = guitar.name,
            content = "组织社员在校园内进行秋景采风，评选优秀作品并举办小型展览。",
            location = "犀浦校区 全校范围",
            requirements = "自备相机或手机",
            capacity = 50,
            registrationDeadline = TimeUtil.plusDays(7),
            startTime = TimeUtil.plusDays(9, 2),
            endTime = TimeUtil.plusDays(9, 6),
            status = ActivityStatus.REJECTED,
            qrToken = "PHOT2026",
            createdAt = TimeUtil.now(),
            reviewerName = teacher.name,
            reviewedAt = TimeUtil.now(),
            reviewComment = "缺少外出/校园聚集活动的安全预案与带队老师信息，请补充后重新提交。",
            imageUrl = "/images/photography.svg"
        )
        listOf(a1, a2, a3, a4, a5, a6).forEach(activities::insert)

        // ---- 报名记录 ----
        val sampleRegistrations = listOf(
            Registration("R-2001", a1.id, s1.id, s1.name, s1.username, TimeUtil.now(), RegistrationStatus.REGISTERED),
            Registration("R-2002", a1.id, s2.id, s2.name, s2.username, TimeUtil.now(), RegistrationStatus.REGISTERED),
            Registration("R-2003", a3.id, s1.id, s1.name, s1.username, TimeUtil.now(), RegistrationStatus.REGISTERED),
            Registration("R-2004", a5.id, s2.id, s2.name, s2.username, TimeUtil.now(), RegistrationStatus.REGISTERED)
        )
        sampleRegistrations.forEach(registrations::insert)
    }
}
