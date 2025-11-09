package vx.ignis.util

import net.md_5.bungee.api.ChatColor

object HexColorLib {

    @JvmStatic
    fun String.color(): String {
        when (this.count{it == '#'}) {
            1 -> {
                val messages = this.split("#")
                val startTag = messages[1].take(6)
                val newMessage = this.replace("#$startTag", "")

                val r1 = startTag.substring(0, 2).rgb() ?: return this
                val g1 = startTag.substring(2, 4).rgb() ?: return this
                val b1 = startTag.substring(4, 6).rgb() ?: return this

                var returnMessage = ""

                returnMessage += ChatColor.of(
                    java.awt.Color(r1.toInt(), g1.toInt(), b1.toInt())
                )
                returnMessage += newMessage

                return returnMessage
            }
            2 -> {
                val messages = this.split("#")
                val startTag = messages[1].take(6)
                val endTag = messages[2].take(6)
                val newmessage = this.replace("#$startTag", "").replace("#$endTag", "")

                var r1 = startTag.substring(0, 2).rgb() ?: return this
                var g1 = startTag.substring(2, 4).rgb() ?: return this
                var b1 = startTag.substring(4, 6).rgb() ?: return this

                val r2 = endTag.substring(0, 2).rgb() ?: return this
                val g2 = endTag.substring(2, 4).rgb() ?: return this
                val b2 = endTag.substring(4, 6).rgb() ?: return this

                val lenght = newmessage.length.toDouble()

                val incrementR = (r1 - r2) / lenght
                val incrementG = (g1 - g2) / lenght
                val incrementB = (b1 - b2) / lenght

                var returnMessage = ""
                for (i in 0 until lenght.toInt()) {
                    returnMessage += ChatColor.of(
                        java.awt.Color(r1.toInt(), g1.toInt(), b1.toInt())
                    )
                    returnMessage += newmessage[i]

                    r1 -= incrementR
                    g1 -= incrementG
                    b1 -= incrementB
                }
                return returnMessage
            }
            else -> {
                return ChatColor.translateAlternateColorCodes('&',this)
            }
        }
    }

    private fun String.rgb(): Double? {
        return try {
            this.toInt(16).toDouble()
        } catch (e : java.lang.NumberFormatException) {
            0.0
        }
    }

}