package com.example.assistant.data.local.converter

import androidx.room.TypeConverter
import com.example.assistant.domain.model.Priority

/** Room type converters. Enums are stored by name so reordering is safe. */
class Converters {
    @TypeConverter
    fun priorityToString(priority: Priority): String = priority.name

    @TypeConverter
    fun stringToPriority(value: String): Priority =
        runCatching { Priority.valueOf(value) }.getOrDefault(Priority.MEDIUM)
}
