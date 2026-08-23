/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chiller3.bcr.R
import com.chiller3.bcr.format.AudioSource

/**
 * Lets the user pick which microphone input the non-root fallback recorder uses.
 *
 * Only unprivileged sources are offered because the `VOICE_*` sources cannot be opened without
 * system app permissions.
 */
@Composable
fun FallbackAudioSourceDialog(
    audioSource: AudioSource,
    onSelect: (AudioSource) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        title = { Text(text = stringResource(R.string.pref_fallback_audio_source_name)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(state = rememberScrollState())
                    .selectableGroup(),
            ) {
                Text(text = stringResource(R.string.pref_fallback_audio_source_desc))

                for (source in AudioSource.UNPRIVILEGED) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = source == audioSource,
                                onClick = { onSelect(source) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = source == audioSource,
                            onClick = null,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = stringResource(source.nameResId))
                    }
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}
