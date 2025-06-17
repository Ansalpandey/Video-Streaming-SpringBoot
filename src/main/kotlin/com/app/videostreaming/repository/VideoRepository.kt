package com.app.videostreaming.repository

import com.app.videostreaming.model.VideoModel
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface VideoRepository : MongoRepository<VideoModel, ObjectId> {}
