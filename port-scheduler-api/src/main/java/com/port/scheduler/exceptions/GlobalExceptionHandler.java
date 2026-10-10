package com.port.scheduler.exceptions;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.google.genai.errors.ServerException;

@ControllerAdvice 
public class GlobalExceptionHandler {
    @ExceptionHandler 
    public ResponseEntity<BerthErrorResponse> getBerthNotFoundResponse(BerthNotFoundException e)
    {
        BerthErrorResponse berthErrorResponse=new BerthErrorResponse();
        berthErrorResponse.setStatusCode(404);
        berthErrorResponse.setMessage("Berth not found: "+e.getMessage());
        berthErrorResponse.setTimeStamp(System.currentTimeMillis());

        return new ResponseEntity<>(berthErrorResponse,org.springframework.http.HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler
    public ResponseEntity<BerthErrorResponse> getAiServiceErrorResponse(ServerException e)
    {
        BerthErrorResponse berthErrorResponse=new BerthErrorResponse();
        berthErrorResponse.setStatusCode(502);
        berthErrorResponse.setMessage("AI service error: "+e.getMessage());
        berthErrorResponse.setTimeStamp(System.currentTimeMillis());

        return new ResponseEntity<>(berthErrorResponse,org.springframework.http.HttpStatus.BAD_GATEWAY);
    }
}
