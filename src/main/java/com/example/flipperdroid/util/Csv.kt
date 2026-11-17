package com.example.flipperdroid.util

/** Quoted CSV field: quotes doubled, CR/LF stripped (RFC 4180). */
fun csvField(s: String): String =
    "\"" + s.replace("\r", "").replace("\n", "").replace("\"", "\"\"") + "\""
