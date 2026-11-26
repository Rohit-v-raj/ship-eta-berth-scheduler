package com.port.scheduler.exceptions;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The body of an error on the console's routes. The order is fixed: Jackson 3
 * (Spring Boot 4) sorts a class's properties alphabetically by default.
 */
@JsonPropertyOrder({"statusCode", "message", "timeStamp"})
public class BerthErrorResponse {
    private int statusCode;
    private String message;
    private long timeStamp;
	public int getStatusCode() {
		return statusCode;
	}
	public void setStatusCode(int statusCode) {
		this.statusCode = statusCode;
	}
	public String getMessage() {
		return message;
	}
	public void setMessage(String message) {
		this.message = message;
	}
	public long getTimeStamp() {
		return timeStamp;
	}
	public void setTimeStamp(long timeStamp) {
		this.timeStamp = timeStamp;
	}

}
