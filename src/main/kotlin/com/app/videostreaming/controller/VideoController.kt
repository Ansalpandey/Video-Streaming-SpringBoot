package com.app.videostreaming.controller

import com.app.videostreaming.model.VideoModel
import com.app.videostreaming.service.VideoService
import org.bson.types.ObjectId
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.core.io.Resource
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/videos")
class VideoController(private val videoService: VideoService) {
    @GetMapping("/{id}")
  fun getVideoById(@PathVariable id: ObjectId): VideoModel {
    return videoService.getVideoById(id)
        ?: throw NoSuchElementException("Video not found with ID: $id")
  }

  @GetMapping
  fun getAllVideos(): List<VideoModel> {
    return videoService.getAllVideos()
  }

  @PostMapping
  fun addVideo(
      @RequestParam file: MultipartFile,
      @RequestParam title: String,
      @RequestParam description: String
  ): String {
    val video =
        VideoModel(
            title = title,
            description = description,
            thumbnailUrl = "https://i.ytimg.com/vi/MxTUvs_wNc8/hqdefault.jpg?sqp=-oaymwEnCNACELwBSFryq4qpAxkIARUAAIhCGAHYAQHiAQoIGBACGAY4AUAB&rs=AOn4CLA3rkoWeXU-9VLsnaeingR24cdlyw",
            filePath = file.originalFilename ?: "unknown",
            contentType = file.contentType ?: "",
        )
    return videoService.addVideo(video, file)
  }

  @DeleteMapping("/{id}")
  fun removeVideo(@PathVariable id: ObjectId): String {
    val video =
        videoService.getVideoById(id)
            ?: throw NoSuchElementException("Video not found with ID: $id")
    return videoService.removeVideo(video)
  }

  @GetMapping("/stream/{id}", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
  fun streamVideo(
    @PathVariable id: ObjectId,
    @RequestHeader(value = "Range", required = false) range: String?
  ): ResponseEntity<Resource> {
    return videoService.streamVideo(id, range)
  }

//    @GetMapping("/{id}/master.m3u8")
//    fun getMasterPlaylist(@PathVariable id: ObjectId): ResponseEntity<Resource> {
//        return videoService.getMasterPlaylist(id)
//    }
//
//    @GetMapping("/{id}/{segment}")
//    fun getHlsSegment(
//        @PathVariable id: ObjectId,
//        @PathVariable segment: String
//    ): ResponseEntity<Resource> {
//        return videoService.getHlsSegment(id, segment)
//    }

    // ✅ New endpoint for MPEG-DASH manifest (.mpd)
    @GetMapping("/{id}/manifest.mpd")
    fun getDashManifest(@PathVariable id: ObjectId): ResponseEntity<Resource> {
        return videoService.getDashManifest(id)
    }

    // ✅ Serve MPEG-DASH segments (.m4s)
    @GetMapping("/{id}/{segment}")
    fun getDashSegment(
        @PathVariable id: ObjectId,
        @PathVariable segment: String
    ): ResponseEntity<Resource> {
        return videoService.getDashSegment(id, segment)
    }

}
