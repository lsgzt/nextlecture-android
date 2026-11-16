package com.gndec.timetable.net

/** One HTTP exchange failed; [code] carries the response status. */
class HttpException(val code: Int, message: String) : Exception("HTTP $code: $message")
