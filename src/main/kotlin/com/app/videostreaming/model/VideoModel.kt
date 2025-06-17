package com.app.videostreaming.model

import jakarta.annotation.Nonnull
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "videos")
@TypeAlias("videos")
data class VideoModel(
    @Id val id: ObjectId? = null,
    @Nonnull val title: String,
    @Nonnull val description: String,
    @Nonnull var filePath: String,
    @Nonnull var contentType: String,
    val thumbnailUrl: String = "",
    @Nonnull val uploader: String = "Unknown",
    @Nonnull val uploadDate: String = "",
    @Nonnull val duration: Int = 0, // Duration in seconds
    @Nonnull val views: Int = 0,
    @Nonnull val likes: Int = 0,
    @Nonnull val dislikes: Int = 0,
    @Nonnull val tags: List<String> = emptyList()
)
