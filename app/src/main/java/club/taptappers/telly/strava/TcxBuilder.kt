package club.taptappers.telly.strava

import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Constructs a minimal TCX 1.0 (TrainingCenterDatabase) document for a single
 * non-GPS workout — the format Strava accepts for indoor / weight-training
 * activities with HR and calorie data but no route.
 *
 * We deliberately stay in the TCX 1.0 baseline (no extensions): Strava's
 * upload pipeline parses these reliably. Sport is fixed to "Other" — the
 * caller PUTs the real `sport_type` (e.g. "WeightTraining") onto the activity
 * after upload, since TCX has no concept of Strava's richer sport taxonomy.
 *
 * If [hrSamples] is empty we still emit one Trackpoint at the start so
 * Strava's parser has a Track to attach the lap to. With no HR at all,
 * [Calories] still records the lap energy and the activity comes back
 * `has_heartrate: false` — fine.
 */
object TcxBuilder {

    data class HrSample(val timeIso: String, val bpm: Long)

    private val ISO_UTC: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    fun build(
        startIso: String,
        elapsedSeconds: Long,
        notes: String,
        calories: Int,
        hrSamples: List<HrSample>
    ): String {
        val sb = StringBuilder(8 * 1024)
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="no"?>""").append('\n')
        sb.append("""<TrainingCenterDatabase""").append('\n')
        sb.append("""    xsi:schemaLocation="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2 http://www.garmin.com/xmlschemas/TrainingCenterDatabasev2.xsd"""").append('\n')
        sb.append("""    xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2"""").append('\n')
        sb.append("""    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">""").append('\n')
        sb.append("  <Activities>\n")
        sb.append("""    <Activity Sport="Other">""").append('\n')
        sb.append("      <Id>").append(escape(startIso)).append("</Id>\n")
        sb.append("      <Lap StartTime=\"").append(escape(startIso)).append("\">\n")
        sb.append("        <TotalTimeSeconds>").append(elapsedSeconds).append("</TotalTimeSeconds>\n")
        sb.append("        <DistanceMeters>0</DistanceMeters>\n")
        sb.append("        <Calories>").append(calories.coerceAtLeast(0)).append("</Calories>\n")
        sb.append("        <Intensity>Active</Intensity>\n")
        sb.append("        <TriggerMethod>Manual</TriggerMethod>\n")
        sb.append("        <Track>\n")
        if (hrSamples.isEmpty()) {
            // Strava's TCX parser expects at least one trackpoint per Track.
            sb.append("          <Trackpoint>\n")
            sb.append("            <Time>").append(escape(startIso)).append("</Time>\n")
            sb.append("          </Trackpoint>\n")
        } else {
            // Skip out-of-window samples defensively — Strava is lenient but a
            // ts before Lap.StartTime sometimes makes the activity render
            // a 0-min duration.
            val startEpoch = Instant.parse(startIso).epochSecond
            val endEpoch = startEpoch + elapsedSeconds
            for (sample in hrSamples) {
                val sampleEpoch = try {
                    Instant.parse(sample.timeIso).epochSecond
                } catch (_: Exception) {
                    continue
                }
                if (sampleEpoch < startEpoch || sampleEpoch > endEpoch) continue
                if (sample.bpm <= 0) continue
                sb.append("          <Trackpoint>\n")
                sb.append("            <Time>").append(escape(sample.timeIso)).append("</Time>\n")
                sb.append("            <HeartRateBpm>\n")
                sb.append("              <Value>").append(sample.bpm).append("</Value>\n")
                sb.append("            </HeartRateBpm>\n")
                sb.append("          </Trackpoint>\n")
            }
        }
        sb.append("        </Track>\n")
        sb.append("      </Lap>\n")
        if (notes.isNotBlank()) {
            sb.append("      <Notes>").append(escape(notes)).append("</Notes>\n")
        }
        sb.append("      <Creator xsi:type=\"Device_t\">\n")
        sb.append("        <Name>Telly</Name>\n")
        sb.append("        <UnitId>0</UnitId>\n")
        sb.append("        <ProductID>0</ProductID>\n")
        sb.append("        <Version>\n")
        sb.append("          <VersionMajor>1</VersionMajor>\n")
        sb.append("          <VersionMinor>0</VersionMinor>\n")
        sb.append("        </Version>\n")
        sb.append("      </Creator>\n")
        sb.append("    </Activity>\n")
        sb.append("  </Activities>\n")
        sb.append("</TrainingCenterDatabase>\n")
        return sb.toString()
    }

    private fun escape(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
