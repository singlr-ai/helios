package ai.singlr.helios.literal;

public interface StatusCodes {
  int OK = 200;
  int CREATED = 201;
  int ACCEPTED = 202;
  int NO_CONTENT = 204;
  int BAD_REQUEST = 400;
  int UNAUTHORIZED = 401;
  int FORBIDDEN = 403;
  int NOT_FOUND = 404;
  int METHOD_NOT_ALLOWED = 405;
  int CONFLICT = 409;
  int TOO_MANY_REQUESTS = 429;
  int INTERNAL_SERVER_ERROR = 500;
  int SERVICE_UNAVAILABLE = 503;
  int GATEWAY_TIMEOUT = 504;
}
