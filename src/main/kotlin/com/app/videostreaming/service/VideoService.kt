package com.app.videostreaming.service

import com.app.videostreaming.model.VideoModel
import com.app.videostreaming.repository.VideoRepository
import org.bson.types.ObjectId
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.InputStreamResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.util.StringUtils
import org.springframework.web.multipart.MultipartFile
import java.io.*
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

@Service
class VideoService(private val videoRepository: VideoRepository) {

  val DIR = "videos/"
  val HLS_DIR = "hls"
  val DASH_DIR = "dash/"

  fun getAllVideos(): List<VideoModel> = videoRepository.findAll()

  fun getVideoById(id: ObjectId?): VideoModel? {
    return videoRepository.findById(id!!).orElse(null)
  }

  fun addVideo(video: VideoModel, file: MultipartFile): String {
    try {
      val fileName = StringUtils.cleanPath(file.originalFilename!!)
      val inputStream: InputStream = file.inputStream

      val cleanFolderPath = StringUtils.cleanPath(DIR) // e.g., "uploads/videos"
      val uploadDirPath = Paths.get(cleanFolderPath)

      // 1. Create the directory if it doesn't exist
      if (!Files.exists(uploadDirPath)) {
        Files.createDirectories(uploadDirPath)
      }

      // 2. Save the file to disk
      val targetPath = uploadDirPath.resolve(fileName)
      Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING)

      // 3. Set file path and content type
      video.filePath = targetPath.toString()
      video.contentType = file.contentType.toString()

      // 4. Save video metadata
      val savedVideo = videoRepository.save(video)

      try {
        // 5. Process video using ffmpeg
        println("Processing video with ID: ${savedVideo.id}")
        processVideo(savedVideo.id)

        return "Video with ID ${savedVideo.id} added and processed successfully."
      } catch (processingEx: Exception) {
        // If video processing fails, delete video file and remove DB entry
        println("Processing failed: ${processingEx.message}")
        processingEx.printStackTrace()

        // Delete the video file from disk
        try {
          Files.deleteIfExists(Paths.get(savedVideo.filePath))
        } catch (fileEx: Exception) {
          println("Failed to delete file: ${fileEx.message}")
          fileEx.printStackTrace()
        }

        // Delete the record from the database
        videoRepository.deleteById(savedVideo.id!!)

        return "Video processing failed and video was removed: ${processingEx.message}"
      }
    } catch (e: Exception) {
      e.printStackTrace()
      return "Error adding or processing video: ${e.message}"
    }
  }

  fun removeVideo(video: VideoModel): String {
    return try {
      videoRepository.delete(video)
      "Video with ID ${video.id} removed successfully."
    } catch (e: Exception) {
      "Error removing video: ${e.message}"
    }
  }

  fun streamVideo(id: ObjectId, rangeHeader: String?): ResponseEntity<Resource> {
    val video = getVideoById(id) ?: throw NoSuchElementException("Video not found with ID: $id")

    val videoFile = File(video.filePath)
    if (!videoFile.exists()) {
      throw FileNotFoundException("Video file not found at path: ${video.filePath}")
    }

    val fileLength = videoFile.length()
    val maxChunkSize = 20 * 1024 * 1024L // 20MB
    val rangeStart: Long
    val rangeEnd: Long

    if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
      val ranges = rangeHeader.removePrefix("bytes=").split("-")
      rangeStart = ranges[0].toLongOrNull() ?: 0L
      val requestedEnd =
          if (ranges.size > 1 && ranges[1].isNotEmpty()) {
            ranges[1].toLongOrNull() ?: (fileLength - 1)
          } else {
            fileLength - 1
          }
      rangeEnd = minOf(requestedEnd, rangeStart + maxChunkSize - 1, fileLength - 1)
    } else {
      rangeStart = 0L
      rangeEnd = minOf(fileLength - 1, maxChunkSize - 1)
    }

    if (rangeStart >= fileLength || rangeEnd >= fileLength || rangeStart > rangeEnd) {
      return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
          .header(HttpHeaders.CONTENT_RANGE, "bytes */$fileLength")
          .build()
    }

    val contentLength = rangeEnd - rangeStart + 1
    val buffer = ByteArray(contentLength.toInt())

    RandomAccessFile(videoFile, "r").use { input ->
      input.seek(rangeStart)
      input.readFully(buffer)
    }

    val resource = ByteArrayResource(buffer)
    val contentType = Files.probeContentType(videoFile.toPath()) ?: "video/mp4"

    return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
        .header(HttpHeaders.CONTENT_TYPE, contentType)
        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
        .header(HttpHeaders.CONTENT_LENGTH, contentLength.toString())
        .header(HttpHeaders.CONTENT_RANGE, "bytes $rangeStart-$rangeEnd/$fileLength")
        .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
        .body(resource)
  }

  //  fun processVideo(id: ObjectId?): String? {
  //    val video = getVideoById(id) ?: throw NoSuchElementException("Video not found with ID: $id")
  //    val videoId = video.id?.toHexString()
  //    val filePath = video.filePath
  //    val videoPath = Paths.get(filePath)
  //
  //    // Base output directory for HLS
  //    val outputPath = Paths.get(HLS_DIR, videoId)
  //
  //    try {
  //      // Create directory if it doesn't exist
  //      Files.createDirectories(outputPath)
  //
  //      // Build the ffmpeg command
  //      val ffmpegCmd =
  //          String.format(
  //              "ffmpeg -i \"%s\" -c:v libx265 -c:a aac -strict -2 -f hls -hls_time 10
  // -hls_list_size 0 -hls_segment_filename \"%s/segment_%%03d.ts\" \"%s/master.m3u8\"\n",
  //              videoPath.toString(),
  //              outputPath.toString(),
  //              outputPath.toString())
  //
  //      println("Running ffmpeg command: $ffmpegCmd")
  //
  //      val processBuilder = ProcessBuilder("cmd.exe", "/c", ffmpegCmd)
  //      processBuilder.inheritIO()
  //      val process = processBuilder.start()
  //      val exitCode = process.waitFor()
  //
  //      if (exitCode != 0) {
  //        throw RuntimeException("Video processing failed with exit code $exitCode")
  //      }
  //
  //      return videoId
  //    } catch (ex: IOException) {
  //      throw RuntimeException("Video processing failed due to I/O error", ex)
  //    } catch (ex: InterruptedException) {
  //      Thread.currentThread().interrupt()
  //      throw RuntimeException("Video processing was interrupted", ex)
  //    }
  //  }
  fun processVideo(id: ObjectId?): String? {
    val video = getVideoById(id) ?: throw NoSuchElementException("Video not found with ID: $id")
    val videoId = video.id?.toHexString() ?: throw IllegalArgumentException("Invalid video ID")

    val inputVideoPath = Paths.get(video.filePath)
    val outputDir = Paths.get(DASH_DIR, videoId)

    try {
      Files.createDirectories(outputDir)

      val inputPath = inputVideoPath.toAbsolutePath().toString()

      // Build FFmpeg command with -filter_complex to generate multiple resolutions
        val outputManifestPath = "manifest.mpd" // just file name now

        val ffmpegCmd = listOf(
            "ffmpeg",
            "-i", inputPath,
            "-filter_complex",
            "[0:v]split=7[v1][v2][v3][v4][v5][v6][v7];" +
                    "[v1]scale=640:360[v1out];" +
                    "[v2]scale=854:480[v2out];" +
                    "[v3]scale=1280:720[v3out];" +
                    "[v4]scale=1920:1080[v4out];" +
                    "[v5]scale=2560:1440[v5out];" +
                    "[v6]scale=3840:2160[v6out];" +
                    "[v7]scale=7680:4320[v7out]",

            // Map video streams
            "-map", "[v1out]",
            "-map", "[v2out]",
            "-map", "[v3out]",
            "-map", "[v4out]",
            "-map", "[v5out]",
            "-map", "[v6out]",
            "-map", "[v7out]",

            // Map audio (only 1 language for now)
            "-map", "0:a",

            // Bitrate settings
            "-b:v:0", "800k",     // 360p
            "-b:v:1", "1400k",    // 480p
            "-b:v:2", "2800k",    // 720p
            "-b:v:3", "5000k",    // 1080p
            "-b:v:4", "8000k",    // 1440p
            "-b:v:5", "14000k",   // 4K
            "-b:v:6", "25000k",   // 8K

            // Encoding settings
            "-c:v", "libx264",
            "-c:a", "aac",
            "-ac", "2",                      // stereo
            "-ar", "48000",                 // audio sample rate

            // DASH output settings
            "-f", "dash",
            "-seg_duration", "4",
            "-use_template", "1",
            "-use_timeline", "1",
            "-adaptation_sets", "id=0,streams=v id=1,streams=a",

            // Segment file names (relative for Shaka compatibility)
            "-init_seg_name", "init-stream\$RepresentationID$.m4s",
            "-media_seg_name", "chunk-stream\$RepresentationID$-\$Number$.m4s",
            outputManifestPath
        )

        val processBuilder = ProcessBuilder(ffmpegCmd)
        processBuilder.directory(outputDir.toFile()) // run inside output dir
        processBuilder.inheritIO()


        val process = processBuilder.start()
      val errorStream = process.errorStream.bufferedReader().readText()
      println("FFmpeg error output: $errorStream")
      val exitCode = process.waitFor()

      if (exitCode != 0) {
        throw RuntimeException("DASH processing failed with exit code $exitCode")
      }

      return videoId
    } catch (ex: IOException) {
      throw RuntimeException("DASH processing failed due to I/O error", ex)
    } catch (ex: InterruptedException) {
      Thread.currentThread().interrupt()
      throw RuntimeException("DASH processing was interrupted", ex)
    }
  }

  fun getDashManifest(id: ObjectId): ResponseEntity<Resource> {
    val video = getVideoById(id) ?: throw NoSuchElementException("Video not found with ID: $id")
    val videoId = video.id?.toHexString() ?: throw IllegalArgumentException("Invalid video ID")

    val manifestPath = Paths.get(DASH_DIR, videoId, "manifest.mpd")
    if (!Files.exists(manifestPath)) {
      throw FileNotFoundException("DASH manifest not found at path: $manifestPath")
    }

    val resource = ByteArrayResource(Files.readAllBytes(manifestPath))

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "application/dash+xml")
        .body(resource)
  }

  fun getDashSegment(id: ObjectId, segment: String): ResponseEntity<Resource> {
    val video = getVideoById(id) ?: throw NoSuchElementException("Video not found with ID: $id")
    val videoId = video.id?.toHexString() ?: throw IllegalArgumentException("Invalid video ID")

    val segmentPath = Paths.get(DASH_DIR, videoId, segment)
    if (!Files.exists(segmentPath)) {
      throw FileNotFoundException("DASH segment not found at path: $segmentPath")
    }

    val resource = InputStreamResource(Files.newInputStream(segmentPath))
    val fileSize = Files.size(segmentPath)
    val contentType = Files.probeContentType(segmentPath) ?: "video/iso.segment"

    return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
        .header(HttpHeaders.CONTENT_TYPE, contentType)
        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
        .header(HttpHeaders.CONTENT_LENGTH, "$fileSize")
        .header(HttpHeaders.CONTENT_RANGE, "bytes 0-${fileSize - 1}/$fileSize")
        .body(resource)
  }

  //    fun getMasterPlaylist(id: ObjectId): ResponseEntity<Resource> {
  //    val video = getVideoById(id) ?: throw NoSuchElementException("Video not found with ID: $id")
  //    val videoId = video.id?.toHexString() ?: throw IllegalArgumentException("Invalid video ID")
  //
  //    val masterPlaylistPath = Paths.get(HLS_DIR, videoId, "master.m3u8")
  //    if (!Files.exists(masterPlaylistPath)) {
  //      throw FileNotFoundException("Master playlist not found at path: $masterPlaylistPath")
  //    }
  //
  //    val resource = ByteArrayResource(Files.readAllBytes(masterPlaylistPath))
  //    return ResponseEntity.ok()
  //        .header(HttpHeaders.CONTENT_TYPE, "application/vnd.apple.mpegurl")
  //        .body(resource)
  //  }
  //
  //  // Serve the HLS segments
  //  fun getHlsSegment(id: ObjectId, segment: String): ResponseEntity<Resource> {
  //    val video = getVideoById(id) ?: throw NoSuchElementException("Video not found with ID: $id")
  //    val videoId = video.id?.toHexString() ?: throw IllegalArgumentException("Invalid video ID")
  //
  //    val segmentPath = Paths.get(HLS_DIR, videoId, segment)
  //    if (!Files.exists(segmentPath)) {
  //      throw FileNotFoundException("HLS segment not found at path: $segmentPath")
  //    }
  //
  //    val resource = ByteArrayResource(Files.readAllBytes(segmentPath))
  //    return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "video/mp2t").body(resource)
  //  }
}
