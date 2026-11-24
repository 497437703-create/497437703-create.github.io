package com.aihuiji.meeting.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aihuiji.meeting.R
import com.aihuiji.meeting.ui.components.BrandLogo
import com.aihuiji.meeting.ui.theme.Palette
import com.aihuiji.meeting.ui.theme.Type
import kotlinx.coroutines.delay

/** S01 splash (≤ 1.2 s). */
@Composable
fun SplashScreen(onReady: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1_200)
        onReady()
    }
    Box(Modifier.fillMaxSize().background(Palette.Bg0), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandLogo(size = 240.dp)
            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.app_name), style = Type.Display)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.splash_tagline), style = Type.Body, color = Palette.Text2)
        }
    }
}
