package com.aihuiji.meeting.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aihuiji.meeting.BuildConfig
import com.aihuiji.meeting.R
import com.aihuiji.meeting.container
import com.aihuiji.meeting.core.model.CaptionSize
import com.aihuiji.meeting.core.model.Lang
import com.aihuiji.meeting.core.model.BarBounds
import com.aihuiji.meeting.core.model.IconPos
import com.aihuiji.meeting.core.model.ScreenSizeClass
import com.aihuiji.meeting.core.model.HunyuanDefaults
import com.aihuiji.meeting.core.model.SecretSlot
import com.aihuiji.meeting.core.model.ServerProfile
import com.aihuiji.meeting.core.model.Templates
import com.aihuiji.meeting.core.model.TranslationDisplay
import com.aihuiji.meeting.core.provider.HealthResult
import com.aihuiji.meeting.data.SettingsStore
import com.aihuiji.meeting.ui.components.BigSwitch
import com.aihuiji.meeting.ui.components.Chip
import com.aihuiji.meeting.ui.components.IconCircleButton
import com.aihuiji.meeting.ui.components.PrimaryButton
import com.aihuiji.meeting.ui.components.SecondaryButton
import com.aihuiji.meeting.ui.components.StatusPill
import com.aihuiji.meeting.ui.components.glass
import com.aihuiji.meeting.ui.components.pressable
import com.aihuiji.meeting.ui.labels.Labels
import com.aihuiji.meeting.ui.labels.ProviderErrorText
import com.aihuiji.meeting.ui.theme.Palette
import com.aihuiji.meeting.ui.theme.Type
import com.aihuiji.meeting.vc.VcLauncher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** S13 settings, behind the admin PIN (§9.4). */
@Composable
fun SettingsScreen(onBack: () -> Unit, onPermissions: () -> Unit) {
    val container = LocalContext.current.container
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var unlocked by remember { mutableStateOf(false) }
    if (!unlocked) {
        PinGate(
            creating = settings.adminPinHash == null,
            onSubmit = { pin ->
                if (settings.adminPinHash == null) {
                    container.settings.setAdminPin(pin); unlocked = true; true
                } else container.settings.checkAdminPin(pin).also { unlocked = it }
            },
            onBack = onBack,
        )
        return
    }
    SettingsContent(onBack, onPermissions)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsContent(onBack: () -> Unit, onPermissions: () -> Unit) {
    val ctx = LocalContext.current
    val container = ctx.container
    val s by container.settings.settings.collectAsStateWithLifecycle()
    val update = container.settings::update
    var changingPin by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Palette.Bg0).padding(48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconCircleButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back), onBack, size = 80.dp)
            Spacer(Modifier.width(24.dp))
            Text(stringResource(R.string.settings_title), style = Type.TitleL)
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Section(stringResource(R.string.settings_server)) {
                    Text(stringResource(R.string.settings_server_hint), style = Type.Caption)
                    s.profiles.sortedBy { it.kind }.forEach { p -> key(p.id) { ProfileEditor(p, active = p.id == s.activeProfileId) } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_fallback), style = Type.Label)
                            Text(stringResource(R.string.settings_fallback_hint), style = Type.Caption, color = Palette.Warning)
                        }
                        BigSwitch(s.allowCloudFallback, { v -> update { it.copy(allowCloudFallback = v) } })
                    }
                }
                Section(stringResource(R.string.settings_meeting_defaults)) {
                    Labeled(stringResource(R.string.field_panel_layout)) {
                        Chip(stringResource(R.string.bar_reset), false, { update { it.copy(captionBar = BarBounds(), captionIcon = IconPos()) } })
                    }
                    Labeled(stringResource(R.string.field_recognition_lang)) {
                        Lang.entries.forEach { l -> Chip(l.nativeName, s.primaryLang == l, { update { it.copy(primaryLang = l) } }) }
                    }
                    Labeled(stringResource(R.string.field_template)) {
                        Templates.all.forEach { t -> Chip(stringResource(Labels.template(t.id)), s.templateId == t.id, { update { it.copy(templateId = t.id) } }) }
                    }
                    Labeled(stringResource(R.string.settings_vc_app)) {
                        VcLauncher.known.forEach { pkg ->
                            Chip(vcName(pkg), s.vcAppPackage == pkg, { update { it.copy(vcAppPackage = pkg) } })
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Section(stringResource(R.string.settings_display)) {
                    Labeled(stringResource(R.string.settings_screen_size)) {
                        ScreenSizeClass.entries.forEach { sz ->
                            Chip(if (sz == ScreenSizeClass.AUTO) stringResource(R.string.size_auto) else "${sz.inches}\"", s.screenSize == sz, { update { it.copy(screenSize = sz) } })
                        }
                    }
                    Labeled(stringResource(R.string.field_font_size)) {
                        CaptionSize.entries.forEach { c -> Chip(stringResource(Labels.size(c)), s.captionSize == c, { update { it.copy(captionSize = c) } }) }
                    }
                    Labeled(stringResource(R.string.more_display)) {
                        TranslationDisplay.entries.forEach { d -> Chip(stringResource(Labels.display(d)), s.translationDisplay == d, { update { it.copy(translationDisplay = d) } }) }
                    }
                    Labeled(stringResource(R.string.settings_backdrop)) {
                        listOf(0f, 0.4f, 0.8f).forEach { v -> Chip("${(v * 100).toInt()}%", s.captionBackdrop == v, { update { it.copy(captionBackdrop = v) } }) }
                    }
                    SwitchRow(stringResource(R.string.more_avatars), s.showAvatars) { v -> update { it.copy(showAvatars = v) } }
                    SwitchRow(stringResource(R.string.more_timestamps), s.showTimestamps) { v -> update { it.copy(showTimestamps = v) } }
                    SwitchRow(stringResource(R.string.settings_reduce_motion), s.reduceMotion) { v -> update { it.copy(reduceMotion = v) } }
                }
                Section(stringResource(R.string.settings_data)) {
                    Labeled(stringResource(R.string.settings_retention)) {
                        listOf(30, 90, 180, 365).forEach { d -> Chip(stringResource(R.string.days, d), s.retentionDays == d, { update { it.copy(retentionDays = d) } }) }
                    }
                    Labeled(stringResource(R.string.settings_cache)) {
                        listOf(10, 20, 50).forEach { n -> Chip("$n", s.localCacheLimit == n, { update { it.copy(localCacheLimit = n) } }) }
                    }
                }
                Section(stringResource(R.string.settings_admin)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SecondaryButton(stringResource(R.string.settings_change_pin), { changingPin = true })
                        SecondaryButton(stringResource(R.string.settings_permissions), onPermissions)
                    }
                    Text(stringResource(R.string.settings_about, BuildConfig.VERSION_NAME), style = Type.Caption)
                }
            }
        }
    }
    if (changingPin) {
        PinGate(creating = true, onSubmit = { pin -> container.settings.setAdminPin(pin); changingPin = false; true }, onBack = { changingPin = false })
    }
}

private fun vcName(pkg: String) = when (pkg) {
    "com.tencent.wemeet.app" -> "腾讯会议"
    "com.alibaba.android.rimet" -> "钉钉"
    "com.ss.android.lark" -> "飞书"
    "com.microsoft.teams" -> "Teams"
    "com.tencent.wework" -> "企业微信"
    else -> pkg
}

@Composable
private fun ProfileEditor(profile: ServerProfile, active: Boolean) {
    val ctx = LocalContext.current
    val container = ctx.container
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val okText = stringResource(R.string.test_ok)
    val failText = stringResource(R.string.test_failed)
    fun save(edit: (ServerProfile) -> ServerProfile) = container.settings.update { s ->
        s.copy(profiles = s.profiles.map { if (it.id == profile.id) edit(it) else it })
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(if (active) Palette.Brand700.copy(alpha = 0.18f) else Palette.Bg1)
            .border(1.dp, if (active) Palette.Brand500 else Palette.Stroke, RoundedCornerShape(24.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Labels.profile(profile.kind)), style = Type.TitleM)
                    Spacer(Modifier.width(16.dp))
                    if (active) StatusPill(stringResource(R.string.profile_active), Palette.Success)
                }
                Text(
                    stringResource(if (profile.isCloud) R.string.profile_hunyuan_cloud_desc else R.string.profile_enterprise_desc),
                    style = Type.Caption,
                )
            }
            if (!active) SecondaryButton(stringResource(R.string.profile_use), { container.settings.update { it.copy(activeProfileId = profile.id) } }, height = 64.dp)
        }
        if (profile.isCloud) {
            ConfigField(stringResource(R.string.field_app_id), profile.appId, "1250000000") { v -> save { it.copy(appId = v.trim()) } }
            SecretField(stringResource(R.string.field_secret_id), profile.id, SecretSlot.SECRET_ID)
            SecretField(stringResource(R.string.field_secret_key), profile.id, SecretSlot.SECRET_KEY)
            SecretField(stringResource(R.string.field_api_key), profile.id, SecretSlot.API_KEY)
            ConfigField(stringResource(R.string.field_llm_url_cloud), profile.llmBaseUrl, stringResource(R.string.default_hint, HunyuanDefaults.CLOUD_LLM_BASE)) { v -> save { it.copy(llmBaseUrl = v.trim()) } }
            ConfigField(stringResource(R.string.field_asr_engine), profile.asrEngine, stringResource(R.string.default_hint, HunyuanDefaults.CLOUD_ASR_ENGINE_ZH)) { v -> save { it.copy(asrEngine = v.trim()) } }
        } else {
            ConfigField(stringResource(R.string.field_onprem_url), profile.baseUrl, stringResource(R.string.field_onprem_url_hint)) { v -> save { it.copy(baseUrl = v.trim()) } }
            ConfigField(stringResource(R.string.field_asr_path), profile.asrPath, stringResource(R.string.default_hint, HunyuanDefaults.ONPREM_ASR_PATH)) { v -> save { it.copy(asrPath = v.trim()) } }
            ConfigField(stringResource(R.string.field_llm_url_onprem), profile.llmBaseUrl, stringResource(R.string.field_llm_url_onprem_hint)) { v -> save { it.copy(llmBaseUrl = v.trim()) } }
            SecretField(stringResource(R.string.field_token), profile.id, SecretSlot.TOKEN)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ConfigField(
                stringResource(R.string.field_translation_model),
                profile.translationModel,
                stringResource(R.string.default_hint, if (profile.isCloud) HunyuanDefaults.CLOUD_TRANSLATION_MODEL else HunyuanDefaults.ONPREM_TRANSLATION_MODEL),
                Modifier.weight(1f),
            ) { v -> save { it.copy(translationModel = v.trim()) } }
            ConfigField(
                stringResource(R.string.field_summary_model),
                profile.summaryModel,
                stringResource(R.string.default_hint, if (profile.isCloud) HunyuanDefaults.CLOUD_SUMMARY_MODEL else HunyuanDefaults.ONPREM_SUMMARY_MODEL),
                Modifier.weight(1f),
            ) { v -> save { it.copy(summaryModel = v.trim()) } }
        }
        if (profile.isCloud) {
            ConfigField(stringResource(R.string.field_share_url), profile.baseUrl, stringResource(R.string.field_onprem_url_hint)) { v -> save { it.copy(baseUrl = v.trim()) } }
        }
        Labeled(stringResource(R.string.field_timeout)) {
            listOf(10, 20, 30, 60).forEach { sec -> Chip(stringResource(R.string.seconds, sec), profile.timeoutSec == sec, { save { it.copy(timeoutSec = sec) } }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.profile_demo), style = Type.Label)
            Spacer(Modifier.width(16.dp))
            BigSwitch(profile.demoBackend, { v -> save { it.copy(demoBackend = v) } })
            Spacer(Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.profile_test), {
                if (testing) return@PrimaryButton
                testing = true
                result = null
                scope.launch {
                    val p = container.settings.value.profiles.first { it.id == profile.id }
                    result = when (val h = container.providers.create(p).gateway.health()) {
                        is HealthResult.Ok -> "$okText · ${h.latencyMs} ms · ${h.capabilities.modelVersion}"
                        is HealthResult.Unavailable -> "$failText · " + (h.error?.let { ProviderErrorText.describe(ctx, it) } ?: h.reason)
                        HealthResult.NotConfigured -> failText
                    }
                    testing = false
                }
            }, height = 64.dp, enabled = !testing)
        }
        val url = if (profile.isCloud) profile.baseUrl else profile.baseUrl.ifBlank { profile.llmBaseUrl }
        if (url.startsWith("http://") || profile.llmBaseUrl.startsWith("http://")) {
            Text(stringResource(R.string.profile_cleartext), style = Type.Caption, color = Palette.Warning)
        }
        result?.let { Text(it, style = Type.Caption, color = if (it.startsWith(okText)) Palette.Success else Palette.Warning) }
    }
}

/** Plain setting (endpoint, model name, AppId) saved shortly after the user stops typing, or on Done. */
@Composable
private fun ConfigField(label: String, value: String, hint: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    val commit by rememberUpdatedState(onChange)
    LaunchedEffect(text) {
        if (text.trim() == value) return@LaunchedEffect
        delay(600)
        commit(text)
    }
    Column(modifier) {
        Text(label, style = Type.Caption)
        Spacer(Modifier.height(6.dp))
        TextInput(text, { text = it }, hint, showSave = false, onDone = { if (text.trim() != value) commit(text) })
    }
}

/** Credential written to the Keystore-backed vault on save; never shown again or stored in settings. */
@Composable
private fun SecretField(label: String, profileId: String, slot: SecretSlot) {
    val vault = LocalContext.current.container.tokens
    var text by remember(profileId, slot) { mutableStateOf("") }
    var saved by remember(profileId, slot) { mutableStateOf(vault.has(slot.key(profileId))) }
    Column {
        Text(label, style = Type.Caption)
        Spacer(Modifier.height(6.dp))
        TextInput(text, { text = it }, stringResource(if (saved) R.string.secret_saved else R.string.secret_unset), secret = true, onDone = {
            vault.put(slot.key(profileId), text.trim())
            saved = text.isNotBlank()
            text = ""
        })
    }
}

@Composable
private fun TextInput(value: String, onChange: (String) -> Unit, hint: String, secret: Boolean = false, showSave: Boolean = true, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(72.dp).glass(16.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = Type.Label.copy(color = Palette.Text1),
            cursorBrush = SolidColor(Palette.Brand400),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(hint, style = Type.Label, color = Palette.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                inner()
            },
        )
        if (showSave && value.isNotEmpty()) {
            Text(stringResource(R.string.action_save), style = Type.Label, color = Palette.Brand400, modifier = Modifier.pressable(onClick = onDone).padding(12.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().glass(28.dp).padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(title, style = Type.TitleM)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, style = Type.Caption)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.Label, modifier = Modifier.weight(1f))
        BigSwitch(value, onChange)
    }
}

/** Large on-screen keypad (no physical keyboard on the board). */
@Composable
private fun PinGate(creating: Boolean, onSubmit: (String) -> Boolean, onBack: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Palette.Bg0), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Lock, null, tint = Palette.Brand400, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(16.dp))
            Text(stringResource(if (creating) R.string.pin_create else R.string.pin_enter), style = Type.TitleL)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (error) R.string.pin_wrong else R.string.pin_hint),
                style = Type.Caption,
                color = if (error) Palette.Danger else Palette.Text2,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(6) { i ->
                    Box(Modifier.size(28.dp).clip(CircleShape).background(if (i < pin.length) Palette.Brand400 else Palette.Bg2))
                }
            }
            Spacer(Modifier.height(32.dp))
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "←", "0", "OK")
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    row.forEach { k ->
                        Box(
                            Modifier.size(120.dp, 96.dp).clip(RoundedCornerShape(28.dp))
                                .then(if (k == "OK") Modifier.background(Palette.BrandGradient) else Modifier.glass(28.dp))
                                .pressable {
                                    error = false
                                    when (k) {
                                        "←" -> pin = pin.dropLast(1)
                                        "OK" -> if (SettingsStore.isValidPin(pin)) {
                                            if (!onSubmit(pin)) { error = true; pin = "" }
                                        } else error = true
                                        else -> if (pin.length < 6) pin += k
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (k == "←") Icon(Icons.AutoMirrored.Outlined.Backspace, null, tint = Palette.Text1, modifier = Modifier.size(40.dp))
                            else Text(k, style = Type.TitleL)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            Spacer(Modifier.height(12.dp))
            SecondaryButton(stringResource(R.string.action_back), onBack)
        }
    }
}
