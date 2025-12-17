package com.telly.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.telly.data.model.ScheduleType
import com.telly.data.model.Tale
import com.telly.ui.theme.Black
import com.telly.ui.theme.Gray100
import com.telly.ui.theme.Gray200
import com.telly.ui.theme.Gray500
import com.telly.ui.theme.White

data class IntervalOption(
    val label: String,
    val valueMs: Long
)

val intervalOptions = listOf(
    IntervalOption("10 seconds", 10_000),
    IntervalOption("30 seconds", 30_000),
    IntervalOption("1 minute", 60_000),
    IntervalOption("5 minutes", 300_000),
    IntervalOption("15 minutes", 900_000),
    IntervalOption("1 hour", 3_600_000)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTaleScreen(
    existingTale: Tale? = null,
    onSave: (Tale) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf(existingTale?.name ?: "") }
    var scheduleType by remember { mutableStateOf(existingTale?.scheduleType ?: ScheduleType.ONCE) }
    var selectedInterval by remember {
        mutableStateOf(
            existingTale?.scheduleValue?.toLongOrNull()?.let { ms ->
                intervalOptions.find { it.valueMs == ms }
            } ?: intervalOptions[2] // Default to 1 minute
        )
    }
    var dailyTime by remember {
        mutableStateOf(
            if (existingTale?.scheduleType == ScheduleType.DAILY_AT)
                existingTale.scheduleValue ?: "07:00"
            else "07:00"
        )
    }

    val isEditing = existingTale != null
    val canSave = name.isNotBlank()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = White,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isEditing) "Edit Tale" else "Create Tale",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = White,
                    titleContentColor = Black,
                    navigationIconContentColor = Black
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Name input
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Name",
                    style = MaterialTheme.typography.labelLarge,
                    color = Black
                )
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Black),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Gray200, RoundedCornerShape(8.dp))
                        .padding(16.dp),
                    decorationBox = { innerTextField ->
                        Box {
                            if (name.isEmpty()) {
                                Text(
                                    text = "Enter tale name",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Gray500
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }

            // Action type (currently just TIME)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Action",
                    style = MaterialTheme.typography.labelLarge,
                    color = Black
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Gray200, RoundedCornerShape(8.dp))
                        .background(Gray100, RoundedCornerShape(8.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Fetch Time",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Gray500
                    )
                }
            }

            // Schedule type
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Schedule",
                    style = MaterialTheme.typography.labelLarge,
                    color = Black
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScheduleType.entries.forEach { type ->
                        ScheduleTypeChip(
                            label = when (type) {
                                ScheduleType.ONCE -> "Once"
                                ScheduleType.INTERVAL -> "Interval"
                                ScheduleType.DAILY_AT -> "Daily"
                            },
                            selected = scheduleType == type,
                            onClick = { scheduleType = type },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Schedule value input based on type
            when (scheduleType) {
                ScheduleType.ONCE -> {
                    Text(
                        text = "Tale will run immediately when enabled",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Gray500
                    )
                }

                ScheduleType.INTERVAL -> {
                    IntervalPicker(
                        selectedOption = selectedInterval,
                        onOptionSelected = { selectedInterval = it }
                    )
                }

                ScheduleType.DAILY_AT -> {
                    TimeInput(
                        time = dailyTime,
                        onTimeChange = { dailyTime = it }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Save button
            Button(
                onClick = {
                    val scheduleValue = when (scheduleType) {
                        ScheduleType.ONCE -> null
                        ScheduleType.INTERVAL -> selectedInterval.valueMs.toString()
                        ScheduleType.DAILY_AT -> dailyTime
                    }

                    val tale = existingTale?.copy(
                        name = name,
                        scheduleType = scheduleType,
                        scheduleValue = scheduleValue
                    ) ?: Tale(
                        name = name,
                        scheduleType = scheduleType,
                        scheduleValue = scheduleValue
                    )

                    onSave(tale)
                },
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Black,
                    contentColor = White,
                    disabledContainerColor = Gray200,
                    disabledContentColor = Gray500
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (isEditing) "Save Changes" else "Create Tale",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun ScheduleTypeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = if (selected) Black else Gray200,
                shape = RoundedCornerShape(8.dp)
            )
            .background(if (selected) Black else White)
            .clickable(onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) White else Gray500
        )
    }
}

@Composable
private fun IntervalPicker(
    selectedOption: IntervalOption,
    onOptionSelected: (IntervalOption) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Repeat every",
            style = MaterialTheme.typography.labelLarge,
            color = Black
        )
        Box {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Gray200, RoundedCornerShape(8.dp))
                    .clickable { expanded = true }
                    .padding(16.dp)
            ) {
                Text(
                    text = selectedOption.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Black
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(White)
            ) {
                intervalOptions.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (option == selectedOption) Black else Gray500
                            )
                        },
                        onClick = {
                            onOptionSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeInput(
    time: String,
    onTimeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Time (HH:MM)",
            style = MaterialTheme.typography.labelLarge,
            color = Black
        )
        BasicTextField(
            value = time,
            onValueChange = { newValue ->
                // Simple validation for HH:MM format
                if (newValue.length <= 5 && newValue.all { it.isDigit() || it == ':' }) {
                    onTimeChange(newValue)
                }
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Black),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Gray200, RoundedCornerShape(8.dp))
                .padding(16.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (time.isEmpty()) {
                        Text(
                            text = "07:00",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Gray500
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}
