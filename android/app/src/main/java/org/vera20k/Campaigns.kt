package org.vera20k

/**
 * The campaign sets shipped with the game, and the mission names inside them.
 *
 * Only names that could be checked against a published mission list are here:
 * a browser that invents mission titles would be worse than one that admits it
 * has nothing. `cn` is the translation used in the 2001 Chinese releases, which
 * differs between localisation builds, so the original operation name is shown
 * next to it as the authoritative one.
 */
internal data class Campaign(
    val title: String,
    val era: String,
    val missions: List<Mission>,
    val note: String = "",
)

internal data class Mission(val index: Int, val code: String, val cn: String)

internal object Campaigns {
    /** The suffix explainer shown above the mission lists. */
    const val MAP_EXTENSION_NOTE =
        ".map 是任务/战役地图的后缀；遭遇战地图则是 .mpr（红警2）、.yrm（尤里的复仇），" +
            "两家通用的压缩版是 .mmx。它们都要放在游戏目录下，引擎才会读到。"

    val ALL: List<Campaign> = listOf(
        Campaign(
            title = "盟军战役",
            era = "红色警戒2",
            missions = listOf(
                Mission(1, "Lone Guardian", "孤守纽约"),
                Mission(2, "Eagle Dawn", "危机黎明"),
                Mission(3, "Hail to the Chief", "为长官欢呼"),
                Mission(4, "Last Chance", "最后机会"),
                Mission(5, "Dark Night", "暗夜"),
                Mission(6, "Liberty", "自由"),
                Mission(7, "Deep Sea", "深海"),
                Mission(8, "Free Gateway", "自由之门"),
                Mission(9, "Sun Temple", "太阳神殿"),
                Mission(10, "Mirage", "海市蜃楼"),
                Mission(11, "Fallout", "辐射尘"),
                Mission(12, "Chrono Storm", "超时空风暴"),
            ),
        ),
        Campaign(
            title = "苏军战役",
            era = "红色警戒2",
            missions = listOf(
                Mission(1, "Red Dawn", "红色黎明"),
                Mission(2, "Hostile Shore", "敌岸登陆"),
                Mission(3, "Big Apple", "大苹果"),
                Mission(4, "Home Front", "本土战线"),
                Mission(5, "City of Lights", "灯火之城"),
                Mission(6, "Sub-Divide", "水下分割"),
                Mission(7, "Chrono Defense", "超时空防御"),
                Mission(8, "Desecration", "亵渎"),
                Mission(9, "The Fox and the Hound", "狐与猎犬"),
                Mission(10, "Weathered Alliance", "风化的同盟"),
                Mission(11, "Red Revolution", "红色革命"),
                Mission(12, "Polar Storm", "极地风暴"),
            ),
        ),
        Campaign(
            title = "盟军战役",
            era = "尤里的复仇",
            missions = listOf(
                Mission(1, "Time Lapse", "光阴似箭"),
                Mission(2, "Hollywood and Vain", "好莱坞与虚荣"),
                Mission(3, "Power Play", "电力竞争"),
                Mission(4, "Tomb Raided", "古墓袭击"),
                Mission(5, "Clones Down Under", "南半球克隆"),
                Mission(6, "Trick or Treaty", "诡计或条约"),
                Mission(7, "Brain Dead", "脑死亡"),
            ),
        ),
        Campaign(
            title = "苏军战役",
            era = "尤里的复仇",
            note = "共 7 关，关卡名未收录：这一组的行动代号没有可核对的公开列表，界面不编造名称。",
            missions = emptyList(),
        ),
    )
}
