package com.app.videostreaming.service

import com.app.videostreaming.model.VideoModel
import com.app.videostreaming.repository.VideoRepository
import java.io.*
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import org.bson.types.ObjectId
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.util.StringUtils
import org.springframework.web.multipart.MultipartFile

@Service
class VideoService(private val videoRepository: VideoRepository) {

  val DIR = "videos/"
  val HSL_DIR = "hsl/"

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
    val inputStream = RandomAccessFile(videoFile, "r")

    val maxChunkSize = 20 * 1024 * 1024L // 20MB per chunk

    val rangeStart: Long
    val rangeEnd: Long

    // Handle byte-range requests
    if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
      val ranges = rangeHeader.removePrefix("bytes=").split("-")
      rangeStart = ranges[0].toLongOrNull() ?: 0L
      val requestedEnd =
          if (ranges.size > 1 && ranges[1].isNotEmpty()) {
            ranges[1].toLongOrNull() ?: (fileLength - 1)
          } else {
            fileLength - 1
          }

      // Cap chunk to maxChunkSize
      rangeEnd = minOf(requestedEnd, rangeStart + maxChunkSize - 1, fileLength - 1)
    } else {
      // No range header = default to beginning, capped by maxChunkSize
      rangeStart = 0
      rangeEnd = minOf(fileLength - 1, maxChunkSize - 1)
    }

    val contentLength = rangeEnd - rangeStart + 1
    inputStream.seek(rangeStart)

    val buffer = ByteArray(contentLength.toInt())
    inputStream.readFully(buffer)
    inputStream.close()

    val resource = ByteArrayResource(buffer)

    return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT) // 206 Partial Content
        .header(HttpHeaders.CONTENT_TYPE, "video/mp4")
        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
        .header(HttpHeaders.CONTENT_LENGTH, contentLength.toString())
        .header(HttpHeaders.CONTENT_RANGE, "bytes $rangeStart-$rangeEnd/$fileLength")
        .body(resource)
  }

  fun processVideo(id: ObjectId?): String? {
    val video = getVideoById(id) ?: throw NoSuchElementException("Video not found with ID: $id")
    val videoId = video.id?.toHexString()
    val filePath = video.filePath
    val videoPath = Paths.get(filePath)

    // Base output directory for HLS
    val outputPath = Paths.get(HSL_DIR, videoId)

    try {
      // Create directory if it doesn't exist
      Files.createDirectories(outputPath)

      // Build the ffmpeg command
      val ffmpegCmd =
          String.format(
              "ffmpeg -i \"%s\" -c:v libx265 -c:a aac -strict -2 -f hls -hls_time 10 -hls_list_size 0 -hls_segment_filename \"%s/segment_%%03d.ts\" \"%s/master.m3u8\"\n",
              videoPath.toString(),
              outputPath.toString(),
              outputPath.toString())

      println("Running ffmpeg command: $ffmpegCmd")

      val processBuilder = ProcessBuilder("cmd.exe", "/c", ffmpegCmd)
      processBuilder.inheritIO()
      val process = processBuilder.start()
      val exitCode = process.waitFor()

      if (exitCode != 0) {
        throw RuntimeException("Video processing failed with exit code $exitCode")
      }

      return videoId
    } catch (ex: IOException) {
      throw RuntimeException("Video processing failed due to I/O error", ex)
    } catch (ex: InterruptedException) {
      Thread.currentThread().interrupt()
      throw RuntimeException("Video processing was interrupted", ex)
    }
  }

    fun getMasterPlaylist(id: ObjectId): ResponseEntity<Resource> {
        val video = getVideoById(id) ?: throw NoSuchElementException("Video not found with ID: $id")
        val videoId = video.id?.toHexString() ?: throw IllegalArgumentException("Invalid video ID")

        val masterPlaylistPath = Paths.get(HSL_DIR, videoId, "master.m3u8")
        if (!Files.exists(masterPlaylistPath)) {
        throw FileNotFoundException("Master playlist not found at path: $masterPlaylistPath")
        }

        val resource = ByteArrayResource(Files.readAllBytes(masterPlaylistPath))
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "application/vnd.apple.mpegurl")
            .body(resource)
    }

  // Serve the HLS segments
    fun getHlsSegment(id: ObjectId, segment: String): ResponseEntity<Resource> {
        val video = getVideoById(id) ?: throw NoSuchElementException("Video not found with ID: $id")
        val videoId = video.id?.toHexString() ?: throw IllegalArgumentException("Invalid video ID")

        val segmentPath = Paths.get(HSL_DIR, videoId, segment)
        if (!Files.exists(segmentPath)) {
        throw FileNotFoundException("HLS segment not found at path: $segmentPath")
        }

        val resource = ByteArrayResource(Files.readAllBytes(segmentPath))
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "video/mp2t")
            .body(resource)
    }
}
