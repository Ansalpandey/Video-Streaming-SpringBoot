package com.app.videostreaming

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class VideoStreamingApplication

fun main(args: Array<String>) {
    runApplication<VideoStreamingApplication>(*args)
}
