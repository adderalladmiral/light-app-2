package com.ks03old.app

/**
 * Direct port of cheshire/hal/compilers/ks03_old/platform_commands.py.
 * All frames start with 0x7E and end with 0xEF, except the music-model
 * frame which is flagged upstream as untested and uses a 0xA5 trailer.
 */
object Ks03OldProtocol {

    fun switch(on: Boolean): ByteArray =
        if (on)
            byteArrayOf(0x7E, 0x04, 0x04, 0xF0.toByte(), 0x01, 0x01, 0xFF.toByte(), 0x00, 0xEF.toByte())
        else
            byteArrayOf(0x7E, 0x04, 0x04, 0x10, 0x01, 0x00, 0xFF.toByte(), 0x00, 0xEF.toByte())

    /** brightness: 0-100 */
    fun brightness(brightness: Int): ByteArray {
        val b = brightness.coerceIn(0, 100).toByte()
        return byteArrayOf(0x7E, 0x04, 0x01, b, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0xEF.toByte())
    }

    /** r, g, b: 0-100 each (device does not accept 0-255) */
    fun rgb(r: Int, g: Int, b: Int): ByteArray {
        return byteArrayOf(
            0x7E, 0x07, 0x05, 0x03,
            r.coerceIn(0, 100).toByte(),
            g.coerceIn(0, 100).toByte(),
            b.coerceIn(0, 100).toByte(),
            0x00, 0xEF.toByte()
        )
    }

    /** speed: raw byte, device-defined range */
    fun speed(speed: Int): ByteArray {
        return byteArrayOf(0x7E, 0x04, 0x02, speed.coerceIn(0, 255).toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0xEF.toByte())
    }

    enum class Scene(val id: Int) {
        JUMP_7(0), JUMP_3(1), FADE_7(2), FADE_3(3), FLASH(4), AUTO(5)
    }

    fun scene(scene: Scene): ByteArray {
        val sceneByte = (scene.id + 128).toByte()
        return byteArrayOf(0x7E, 0x05, 0x03, sceneByte, 0x03, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0xEF.toByte())
    }

    enum class MusicModel(val id: Int) {
        FADE_7_FAST_ON_NOISE(0),
        TWO_FADE_FAST_ON_NOISE(1),
        JUMP_ON_NOISE_PAUSE_QUIET(2),
        JUMP_ON_NOISE_OFF_QUIET(3)
    }

    /** Unverified upstream — trailer is 0xA5, not 0xEF, unlike every other frame. */
    fun musicModel(model: MusicModel, speed: Int): ByteArray {
        val invertedSpeed = (8 - speed.coerceIn(0, 8)).toByte()
        return byteArrayOf(0x7E, 0x0A, model.id.toByte(), invertedSpeed, 0xA5.toByte())
    }
}
