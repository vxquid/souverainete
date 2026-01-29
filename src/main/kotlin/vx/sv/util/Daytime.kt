package vx.sv.util

enum class Daytime {

    MORNING, DAY, EVENING, NIGHT;

    companion object {
        fun fromWorldTime(time: Long): Daytime {
            val normalizedTime = time % 24000
            return when (normalizedTime) {
                in 0..5999 -> MORNING
                in 6000..11999 -> DAY
                in 12000..17999 -> EVENING
                else -> NIGHT // 18000-23999
            }
        }
    }

}