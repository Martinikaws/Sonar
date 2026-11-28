package app.sonar.ui.settings

import androidx.annotation.StringRes
import app.sonar.R
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
    SOURCES(R.string.settings_group_sources, R.string.settings_group_sources_summary),
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
    Looks(R.string.looks_title),
    WebApi(R.string.web_api),
    Flac(R.string.flac_settings),
    Hidden(R.string.hidden_settings_title),
    LocalPlaylists(R.string.hidden_local_title),
    Extensions(R.string.ext_title),
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
        "webapi", R.string.web_api, null, listOf(R.string.client_id), SettingsGroup.SETUP,
        SettingsAction.Open(SettingsPage.WebApi),
    ),
    SettingsEntry("tutorial", R.string.guide, null, listOf(R.string.see_setup_again), SettingsGroup.SETUP),
    SettingsEntry("astral-import", R.string.astral_import_title, null, emptyList(), SettingsGroup.SETUP),
    SettingsEntry("pass", R.string.pass_title, null, listOf(R.string.pass_scan), SettingsGroup.SETUP),

    SettingsEntry("web-sdk-recovery", R.string.web_sdk_settings_title, null, listOf(R.string.web_sdk_settings_open), SettingsGroup.SOUND),
    SettingsEntry("backend", R.string.backend, null, listOf(R.string.backend_spotify, R.string.backend_youtube), SettingsGroup.SOUND),
    SettingsEntry(
        "engine", R.string.engine, null,
        listOf(
            R.string.engine_librespot, R.string.engine_soloist, R.string.soloist_setup,
            R.string.soloist_step_key, R.string.soloist_get_key, R.string.soloist_key,
            R.string.soloist_step_download, R.string.soloist_step_account,
        ),
        SettingsGroup.SOUND,
    ),
    SettingsEntry(
        "equalizer", R.string.equalizer, R.string.page_equalizer_summary,
        listOf(R.string.eq_curve, R.string.eq_preamp, R.string.eq_reset, R.string.eq_presets, R.string.eq_save_preset),
        SettingsGroup.SOUND,
        SettingsAction.Open(SettingsPage.Equalizer),
    ),
    SettingsEntry("quality", R.string.quality, null, listOf(R.string.stream_quality_normal, R.string.stream_quality_high, R.string.stream_quality_very_high), SettingsGroup.SOUND),
    SettingsEntry(
        "youtube-quality", R.string.quality, null,
        listOf(R.string.quality_youtube_auto, R.string.quality_youtube_best, R.string.quality_youtube_saver), SettingsGroup.SOUND,
    ),
    SettingsEntry("effect-quality", R.string.effect_quality, null, emptyList(), SettingsGroup.SOUND),

    SettingsEntry(
        "audio-sources", R.string.settings_group_sources, null,
        listOf(
            R.string.audio_source_spotify, R.string.audio_source_flac, R.string.audio_source_youtube,
            R.string.youtube_audio_fallback, R.string.youtube_audio_fallback_summary,
        ),
        SettingsGroup.SOURCES,
    ),

    SettingsEntry(
        "flac-sources", R.string.flac_settings, R.string.flac_settings_summary,
        listOf(R.string.flac_jamendo, R.string.flac_navidrome, R.string.flac_archive, R.string.flac_bandcamp, R.string.flac_massive),
        SettingsGroup.SOURCES, SettingsAction.Open(SettingsPage.Flac),
    ),

    SettingsEntry(
        "keep-playing", R.string.keep_playing_on_close, null,
        listOf(R.string.pause_for_other_apps, R.string.sponsor_block), SettingsGroup.LISTENING,
    ),
    SettingsEntry(
        "power", R.string.power_settings, null,
        listOf(R.string.power_headset_seek, R.string.power_battery_saver, R.string.power_data_saver, R.string.power_restart_player),
        SettingsGroup.LISTENING,
    ),
    SettingsEntry("crossfade", R.string.crossfade, null, listOf(R.string.trim_silence, R.string.skip_fade, R.string.automix_title, R.string.automix_editor), SettingsGroup.LISTENING),
    SettingsEntry("autoplay", R.string.autoplay, null, listOf(R.string.autoplay_infinite_title), SettingsGroup.LISTENING),
    SettingsEntry("spotify-dj", R.string.spotify_dj, null, listOf(R.string.spotify_dj_title), SettingsGroup.LISTENING),
    SettingsEntry("connected-device-label", R.string.connected_device_label, null, emptyList(), SettingsGroup.LISTENING),

    SettingsEntry("player-haptics", R.string.haptics_player_controls, null, emptyList(), SettingsGroup.LISTENING),

    SettingsEntry("lyrics-source", R.string.lyrics_preferred_title, null, emptyList(), SettingsGroup.LYRICS),
    SettingsEntry("lyrics-keep-awake", R.string.haptics_lyrics_keep_awake, null, emptyList(), SettingsGroup.LYRICS),
    SettingsEntry("lyrics-look", R.string.look_title, null, emptyList(), SettingsGroup.LYRICS),
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

    SettingsEntry(
        "hidden-content", R.string.hidden_settings_title, R.string.hidden_settings_summary,
        listOf(R.string.hidden_songs, R.string.hidden_artists), SettingsGroup.LISTENING,
        SettingsAction.Open(SettingsPage.Hidden),
    ),
    SettingsEntry(
        "local-playlists", R.string.hidden_local_title, R.string.hidden_local_summary,
        listOf(R.string.hidden_local_new), SettingsGroup.LISTENING,
        SettingsAction.Open(SettingsPage.LocalPlaylists),
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
    SettingsEntry("looks", R.string.looks_title, R.string.looks_summary, listOf(R.string.looks_spin_cover, R.string.looks_cover_colors, R.string.looks_accent, R.string.looks_shuffle, R.string.looks_repeat, R.string.looks_lyrics, R.string.looks_source), SettingsGroup.APPEARANCE, SettingsAction.Open(SettingsPage.Looks)),
    SettingsEntry("bar", R.string.page_app_bar, null, listOf(R.string.bar_folds), SettingsGroup.APPEARANCE),
    SettingsEntry("font", R.string.font_title, null, listOf(R.string.font_system, R.string.font_serif_artists), SettingsGroup.APPEARANCE),

    SettingsEntry(
        "system", R.string.page_app_system, null,
        listOf(R.string.app_system_settings, R.string.app_battery_settings), SettingsGroup.SYSTEM,
    ),
    SettingsEntry("extensions", R.string.ext_title, null, listOf(R.string.ext_install), SettingsGroup.SYSTEM, SettingsAction.Open(SettingsPage.Extensions)),
    SettingsEntry("links", R.string.spotify_links, null, listOf(R.string.open_by_default), SettingsGroup.SYSTEM),

    SettingsEntry("author", R.string.fix2_upstream_credit, null, emptyList(), SettingsGroup.ABOUT),
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

internal fun glassSearchKeywords(
    supported: Boolean,
    liquid: Boolean,
    ios27: Boolean,
    tinted: Boolean,
    profiles: List<Int>,
): List<Int> = if (!supported) emptyList() else buildList {
    addAll(profiles)
    addAll(listOf(
        R.string.glass_vibrancy, R.string.glass_tint, R.string.glass_film, R.string.glass_indicator,
        R.string.glass_where_bar, R.string.glass_where_mini_player, R.string.glass_where_player,
        R.string.glass_reset,
    ))
    if (liquid) {
        addAll(listOf(
            R.string.glass_ios27, R.string.glass_blur, R.string.glass_rim,
            R.string.glass_refraction_depth, R.string.glass_refraction_amount,
            R.string.glass_dispersion, R.string.glass_depth,
        ))
        if (ios27) add(R.string.glass_frost)
    }
    if (tinted) addAll(listOf(R.string.glass_tint_clear, R.string.glass_tint_strength))
}

/**
 * Every query word must prefix a word in the entry's title, subtitle or keywords.
 * A match against the group title alone returns the whole available group.
 * Case and accents are ignored. A query without words finds everything.
 *
 * [text] turns a string resource into its words, so this stays testable
 * without Android.
 */
internal fun matchSettings(
    entries: List<SettingsEntry>,
    query: String,
    text: (Int) -> String,
): List<SettingsEntry> {
    val words = searchWords(query)
    if (words.isEmpty()) return entries
    val matchingGroups = entries.map { it.group }.distinct().filter {
        words.prefixMatch(searchWords(text(it.title)))
    }
    return entries.filter { entry ->
        entry.group in matchingGroups || words.prefixMatch(buildList {
            add(entry.titleRes)
            entry.subtitleRes?.let(::add)
            addAll(entry.keywords)
        }.flatMap { searchWords(text(it)) })
    }
}

private fun List<String>.prefixMatch(tokens: List<String>): Boolean =
    all { word -> tokens.any { it.startsWith(word) } }

private val WORD = Regex("[\\p{L}\\p{N}]+")
private val MARKS = Regex("\\p{M}+")

private fun searchWords(text: String): List<String> {
    val folded = Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT)
    return WORD.findAll(folded).map { it.value }.toList()
}
