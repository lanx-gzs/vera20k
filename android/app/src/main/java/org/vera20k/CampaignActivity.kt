package org.vera20k

import android.app.Activity
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The campaign browser: the two story campaigns of Red Alert 2 and the two of
 * Yuri's Revenge, each with the missions that could be verified.
 *
 * The missions themselves live inside `ra2.mix` / `ra2md.mix`, which this app
 * cannot enumerate (MIX stores no file names, only hashes), so this screen is a
 * reference — what exists, what it is called — not a launcher for one mission.
 */
class CampaignActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COL_BG
        window.navigationBarColor = COL_BG

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(26))
            setBackgroundColor(COL_BG)
        }
        column.addView(buildHeader())
        column.addView(buildExtensionNote())
        for (campaign in Campaigns.ALL) column.addView(buildSection(campaign))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(COL_BG)
            addView(column)
        })
    }

    private fun buildHeader(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(14))
        }
        val back = TextView(this).apply {
            text = "‹"
            sp(20f)
            gravity = Gravity.CENTER
            setTextColor(COL_TEXT)
            background = ripple(square(COL_SURFACE_HI, 11))
            contentDescription = "返回"
            setOnClickListener { finish() }
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        titles.addView(TextView(this).apply {
            text = "战役"
            sp(19f)
            setTextColor(COL_TEXT)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        titles.addView(TextView(this).apply {
            text = "红色警戒2 · 尤里的复仇"
            sp(11f)
            setTextColor(COL_TEXT_DIM)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(back, LinearLayout.LayoutParams(dp(38), dp(38)))
        row.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun buildExtensionNote(): TextView = TextView(this).apply {
        text = Campaigns.MAP_EXTENSION_NOTE
        sp(11f)
        setTextColor(COL_TEXT_DIM)
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = pill(0xFF171B21.toInt(), 14, COL_OUTLINE)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(14) }
    }

    private fun buildSection(campaign: Campaign): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }
        val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textColumn.addView(TextView(this).apply {
            text = campaign.title
            sp(15f)
            setTextColor(COL_TEXT)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        textColumn.addView(TextView(this).apply {
            text = campaign.era
            sp(11f)
            setTextColor(COL_TEXT_DIM)
            setPadding(0, dp(2), 0, 0)
        })
        val count = TextView(this).apply {
            text = if (campaign.missions.isEmpty()) "7 关" else "${campaign.missions.size} 关"
            sp(12f)
            setTextColor(COL_ACCENT)
            setPadding(dp(9), dp(3), dp(9), dp(3))
            background = pill(0x1AEFB44C, 8, 0x00FFFFFF)
        }
        head.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(count)
        card.addView(head)

        if (campaign.note.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = campaign.note
                sp(11f)
                setTextColor(COL_TEXT_FAINT)
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(dp(16), 0, dp(16), dp(14))
            })
        }

        for (mission in campaign.missions) {
            card.addView(buildMissionRow(mission))
        }
        return card
    }

    private fun buildMissionRow(mission: Mission): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setOnClickListener {
                Toast.makeText(
                    this@CampaignActivity,
                    "第 ${mission.index} 关 · ${mission.code}（${mission.cn}）",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        val number = TextView(this).apply {
            text = String.format("%02d", mission.index)
            sp(11f)
            setTextColor(COL_TEXT_FAINT)
            gravity = Gravity.CENTER
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        titles.addView(TextView(this).apply {
            text = mission.cn
            sp(13f)
            setTextColor(COL_TEXT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        titles.addView(TextView(this).apply {
            text = mission.code
            sp(10f)
            setTextColor(COL_TEXT_FAINT)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(number, LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }
}
