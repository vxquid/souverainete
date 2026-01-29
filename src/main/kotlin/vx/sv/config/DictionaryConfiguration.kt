package vx.sv.config

import vx.sv.Souverainete.Companion.gson
import vx.sv.config.lib.annotations.Comment
import vx.sv.config.lib.annotations.Configuration
import vx.sv.gameplay.dictionary.CustomItem
import vx.sv.gameplay.dictionary.CustomItemDictionary

@Configuration("dictionary.yml")
data class DictionaryConfiguration(

    @Comment("A list of custom items that can be used as quest items (or quest rewards) in the future.")
    var dictionary: MutableMap<String, String> = mutableMapOf(
        "RED_DIAMOND" to gson.toJson(CustomItemDictionary.createRedDiamond(), CustomItem::class.java),
        "SPECIAL_COIN" to gson.toJson(CustomItemDictionary.createSpecialCoin(), CustomItem::class.java),
    )

)