package edu.swjtu.campus.service

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.client.j2se.MatrixToImageWriter
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** 使用 ZXing 本地生成报名二维码 PNG（不依赖任何外部图片服务） */
object QrService {

    fun png(content: String, size: Int = 320): ByteArray {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val image = MatrixToImageWriter.toBufferedImage(matrix)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    /** 二维码里编码的地址：学生扫码后前端会自动跳转到报名页 */
    fun registrationUrl(baseUrl: String, qrToken: String): String =
        baseUrl.trimEnd('/') + "/#/scan/" + qrToken
}
