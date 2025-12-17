package com.telly.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.telly.data.model.Tale
import com.telly.ui.components.TaleCard
import com.telly.ui.theme.Black
import com.telly.ui.theme.Gray500
import com.telly.ui.theme.White

@Composable
fun HomeScreen(
    tales: List<Tale>,
    onCreateTale: () -> Unit,
    onTaleClick: (Tale) -> Unit,
    onToggleTale: (Tale, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = White,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateTale,
                containerColor = Black,
                contentColor = White,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create Tale")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Header
            Text(
                text = "telly",
                style = MaterialTheme.typography.headlineLarge,
                color = Black,
                modifier = Modifier.padding(24.dp)
            )

            if (tales.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "No tales yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = Gray500
                        )
                        Text(
                            text = "Tap + to create your first tale",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Gray500
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(tales, key = { it.id }) { tale ->
                        TaleCard(
                            tale = tale,
                            onClick = { onTaleClick(tale) },
                            onToggleEnabled = { enabled -> onToggleTale(tale, enabled) }
                        )
                    }
                }
            }
        }
    }
}
