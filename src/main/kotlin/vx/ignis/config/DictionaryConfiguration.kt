package vx.ignis.config

import de.exlll.configlib.Comment
import de.exlll.configlib.Configuration
import vx.ignis.Ignis.Companion.gson
import vx.ignis.gameplay.dictionary.CustomItem
import vx.ignis.gameplay.dictionary.CustomItemDictionary

@Configuration
data class DictionaryConfiguration(

    @Comment("A list of custom items that can be used as quest items (or quest rewards) in the future.")
    var dictionary: MutableMap<String, String> = mutableMapOf(
        "RED_DIAMOND" to gson.toJson(CustomItemDictionary.createRedDiamond(), CustomItem::class.java),
        "SPECIAL_COIN" to gson.toJson(CustomItemDictionary.createSpecialCoin(), CustomItem::class.java),
    )

)