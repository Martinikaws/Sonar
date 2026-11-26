package app.moonvault.ui.settings

import androidx.annotation.StringRes
import app.moonvault.R
import java.text.Normalizer
import java.util.Locale

/**
 * The groups of the settings screen, in the order they are drawn.
 *
 * Adding a group is one line here plus its entries in [SETTINGS_INDEX]; the
 * screen draws whatever this list holds. Its mark is chosen in the screen.
 */
internal enum class SettingsGroup(@StringRes val title: Int, @StringRes val summary: Int) {
    ACCOUNT(R.string.account, R.string.page_account_summary),
    SETUP(R.string.settings_group_setup, R.string.settings_group_setup_summary),
    SOUND(R.string.settings_group_sound, R.string.settings_group_sound_summary),
    LISTENING(R.string.settings_group_listening, R.string.settings_group_listening_summary),
    LYRICS(R.string.settings_group_lyrics, R.string.settings_group_lyrics_summary),
    COVERS(R.string.settings_group_covers, R.string.settings_group_covers_summary),
    DOWNLOADS(R.string.downloads, R.string.settings_group_downloads_summary),
    CLEANUP(R.string.storage_cleanup, R.string.settings_group_cleanup_summary),
    LANGUAGE(R.string.language, R.string.settings_group_language_summary),
    APPEARANCE(R.string.settings_group_appearance, R.string.settings_group_appearance_summary),
    SYSTEM(R.string.page_app_system, R.string.settings_group_system_summary),
    ABOUT(R.string.about, R.string.settings_group_about_summary),
    HELP(R.string.help_feedback, R.string.settings_group_help_summary),
    PERMISSIONS(R.string.permissions_asked, R.string.settings_group_permissions_summary),
}

/** The screens that open over the list: each needs more room than a row. */
internal enum class SettingsPage(@StringRes val title: Int) {
    Equalizer(R.string.equalizer),
    Glass(R.string.glass),
    WebApi(R.string.web_api),
}

/** What choosing an entry does: it is drawn in the list, or it opens a page. */
internal sealed interface SettingsAction {
    object Inline : SettingsAction
    data class Open(val page: SettingsPage) : SettingsAction
}

/**
 * One block of the settings list, as search sees it.
 *
 * [keywords] are the labels of the rows inside the block, so searching for a
 * switch finds the block that holds it in whatever language the app is in.
 */
internal data class SettingsEntry(
    /** The block's key in the list; unique. */
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int?,
    val keywords: List<Int>,
    val group: SettingsGroup,
    val action: SettingsAction = SettingsAction.Inline,
)

/** Every block of the settings screen, in drawing order within its group. */
internal val SETTINGS_INDEX: List<SettingsEntry> = listOf(
    SettingsEntry("account", R.string.account, null, listOf(R.string.connect_device), SettingsGroup.ACCOUNT),
    SettingsEntry(
        "youtube-account", R.string.youtube_account, null,
        listOf(R.string.youtube_sign_in, R.string.youtube_sign_out, R.string.youtube_channel), SettingsGroup.ACCOUNT,
    ),
    SettingsEntry("logout", R.string.log_out, null, emptyList(), SettingsGroup.ACCOUNT),

    SettingsEntry(
        "webapi", R.string.web_api, null, listOf(R.string.client_id, R.string.disconnect), SettingsGroup.SETUP,
        SettingsAction.Open(SettingsPage.WebApi),
    ),
    SettingsEntry("tutorial", R.string.guide, null, listOf(R.string.see_setup_again), SettingsGroup.SETUP),

    SettingsEntry("web-sdk-recovery", R.string.web_sdk_settings_title, null, listOf(R.string.web_sdk_settings_open), SettingsGroup.SOUND),
    SettingsEntry("backend", R.string.backend, null, listOf(R.string.backend_spotify, R.string.backend_youtube), SettingsGroup.SOUND),
    SettingsEntry(
        "engine", R.string.engine, null,
        listOf(R.string.engine_librespot, R.string.engine_soloist, R.string.soloist_setup), SettingsGroup.SOUND,
    ),
    SettingsEntry(
        "equalizer", R.string.equalizer, R.string.page_equalizer_summary, emptyList(), SettingsGroup.SOUND,
        SettingsAction.Open(SettingsPage.Equalizer),
    ),
    SettingsEntry("quality", R.string.quality, null, emptyList(), SettingsGroup.SOUND),
    SettingsEntry(
        "youtube-quality", R.string.quality, null,
        listOf(R.string.youtube_quality_auto, R.string.youtube_quality_high, R.string.youtube_quality_low), SettingsGroup.SOUND,
    ),
    SettingsEntry("effect-quality", R.string.effect_quality, null, emptyList(), SettingsGroup.SOUND),

    SettingsEntry(
        "keep-playing", R.string.keep_playing_on_close, null,
        listOf(R.string.pause_for_other_apps, R.string.sponsor_block), SettingsGroup.LISTENING,
    ),
    SettingsEntry("crossfade", R.string.crossfade, null, listOf(R.string.trim_silence, R.string.skip_fade), SettingsGroup.LISTENING),
    SettingsEntry("autoplay", R.string.autoplay, null, listOf(R.string.autoplay_infinite_title), SettingsGroup.LISTENING),
    SettingsEntry("spotify-dj", R.string.spotify_dj, null, listOf(R.string.spotify_dj_title), SettingsGroup.LISTENING),
    SettingsEntry("connected-device-label", R.string.connected_device_label, null, emptyList(), SettingsGroup.LISTENING),

    SettingsEntry("lyrics-source", R.string.lyrics_preferred_title, null, emptyList(), SettingsGroup.LYRICS),
    SettingsEntry("lyrics-folder", R.string.lyrics_folder_title, null, listOf(R.string.lyrics_folder_choose), SettingsGroup.LYRICS),
    SettingsEntry("spicy-lyrics", R.string.spicy_lyrics_title, null, listOf(R.string.spicy_lyrics_key), SettingsGroup.LYRICS),

    SettingsEntry("canvas", R.string.canvas, null, listOf(R.string.canvas_show, R.string.animated_cover_source), SettingsGroup.COVERS),

    SettingsEntry(
        "storage-chart", R.string.download_storage, null,
        listOf(R.string.storage_music, R.string.storage_extras, R.string.storage_caches), SettingsGroup.DOWNLOADS,
    ),
    SettingsEntry(
        "downloads", R.string.downloads, null,
        listOf(
            R.string.download_quality, R.string.download_wifi_only, R.string.download_liked_songs,
            R.string.offline_mode, R.string.download_refetch_extras, R.string.remove_all_downloads,
        ),
        SettingsGroup.DOWNLOADS,
    ),

    SettingsEntry(
        "cleanup", R.string.storage_cleanup, null,
        listOf(R.string.app_clear_list_cache, R.string.app_clear_cache), SettingsGroup.CLEANUP,
    ),

    SettingsEntry("language", R.string.language, null, listOf(R.string.system_language), SettingsGroup.LANGUAGE),

    SettingsEntry(
        "theme", R.string.theme_title, null,
        listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark, R.string.theme_pure_black), SettingsGroup.APPEARANCE,
    ),
    SettingsEntry(
        "glass", R.string.glass, R.string.page_glass_summary, emptyList(), SettingsGroup.APPEARANCE,
        SettingsAction.Open(SettingsPage.Glass),
    ),
    SettingsEntry("bar", R.string.page_app_bar, null, listOf(R.string.bar_folds), SettingsGroup.APPEARANCE),
    SettingsEntry("font", R.string.font_title, null, listOf(R.string.font_system, R.string.font_serif_artists), SettingsGroup.APPEARANCE),

    SettingsEntry(
        "system", R.string.page_app_system, null,
        listOf(R.string.app_system_settings, R.string.app_battery_settings), SettingsGroup.SYSTEM,
    ),
    SettingsEntry("links", R.string.spotify_links, null, listOf(R.string.open_by_default), SettingsGroup.SYSTEM),

    SettingsEntry("author", R.string.developed_by, null, listOf(R.string.support_development), SettingsGroup.ABOUT),
    SettingsEntry("update", R.string.check_for_updates, null, listOf(R.string.version), SettingsGroup.ABOUT),
    SettingsEntry(
        "about", R.string.about, null,
        listOf(R.string.licences, R.string.save_report, R.string.send_report), SettingsGroup.ABOUT,
    ),

    SettingsEntry(
        "help", R.string.help_feedback, null,
        listOf(R.string.telegram_community, R.string.help_discussions, R.string.help_report_problem), SettingsGroup.HELP,
    ),

    SettingsEntry("permissions", R.string.permissions_asked, null, emptyList(), SettingsGroup.PERMISSIONS),
)

/**
 * The entries a query finds: every word of it, in any order, somewhere in the
 * entry's title, subtitle, keywords or group title and summary. Case and accents are
 * ignored. A blank query finds everything.
 *
 * [text] turns a string resource into its words, so this stays testable
 * without Android.
 */
internal fun matchSettings(
    entries: List<SettingsEntry>,
    query: String,
    text: (Int) -> String,
): List<SettingsEntry> {
    val words = foldForSearch(query).split(WHITESPACE).filter { it.isNotEmpty() }
    if (words.isEmpty()) return entries
    return entries.filter { entry ->
        val haystack = buildList {
            add(entry.titleRes)
            entry.subtitleRes?.let(::add)
            addAll(entry.keywords)
            add(entry.group.title)
            add(entry.group.summary)
        }.joinToString(" ") { foldForSearch(text(it)) }
        words.all { it in haystack }
    }
}

private val WHITESPACE = Regex("\\s+")
private val MARKS = Regex("\\p{M}+")

internal fun foldForSearch(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT)
