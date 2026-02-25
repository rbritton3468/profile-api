package com.cardsconnected.profileapi;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

@RestController
@RequestMapping("/profile")
public class ProfileController {

  @GetMapping("/health")
  public Map<String, Object> health() {
    return Map.of("ok", true);
  }

  @GetMapping("/db-test")
  public ResponseEntity<Map<String, Object>> dbTest() {
    String sql = "select 1 as ok";
    try (Connection conn = db();
         PreparedStatement ps = conn.prepareStatement(sql);
         var rs = ps.executeQuery()) {
      if (rs.next()) {
        return ResponseEntity.ok(Map.of("ok", rs.getInt("ok") == 1));
      }
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "No rows returned"));
    } catch (SQLException sqlException) {
      StringBuilder details = new StringBuilder();
      SQLException cur = sqlException;
      while (cur != null) {
        details.append("[sqlState=").append(cur.getSQLState())
          .append(", code=").append(cur.getErrorCode())
          .append(", msg=").append(cur.getMessage()).append("] ");
        cur = cur.getNextException();
      }
      if (sqlException.getCause() != null) {
        details.append("cause=").append(sqlException.getCause());
      }
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("error", "Database error: " + details));
    } catch (IllegalStateException stateException) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("error", "Config error: " + stateException.getMessage()));
    }
  }

  @PostMapping("/upsert")
  public ResponseEntity<Map<String, Object>> upsert(
    @RequestHeader(value = "Authorization", required = false) String authHeader,
    @RequestBody ProfilePayload payload
  ) {
    try {
      if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Missing bearer token"));
      }

      FirebaseToken decoded;
      try {
        decoded = FirebaseAuth.getInstance().verifyIdToken(authHeader.substring(7));
      } catch (Exception ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid Firebase ID token"));
      }

      if (missingRequired(payload)) {
        return ResponseEntity.badRequest().body(Map.of("error", "Missing required profile fields"));
      }

      upsertUser(decoded.getUid(), decoded.getEmail(), payload);
      return ResponseEntity.ok(Map.of("ok", true));
    } catch (SQLException sqlException) {
      StringBuilder details = new StringBuilder();
      SQLException cur = sqlException;
      while (cur != null) {
        details.append("[sqlState=").append(cur.getSQLState())
          .append(", code=").append(cur.getErrorCode())
          .append(", msg=").append(cur.getMessage()).append("] ");
        cur = cur.getNextException();
      }
      if (sqlException.getCause() != null) {
        details.append("cause=").append(sqlException.getCause());
      }
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("error", "Database error: " + details));
    } catch (IllegalStateException stateException) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("error", "Config error: " + stateException.getMessage()));
    }
  }

  private void upsertUser(String uid, String email, ProfilePayload p) throws SQLException {
    String sql = """
      insert into users (
        firebase_uid,email,first_name,last_name,birthday,address_private,
        address_line1,address_line2,city,state_region,postal_code,country_code,
        photo_s3_key,photo_url
      ) values (?, ?, ?, ?, ?, true, ?, ?, ?, ?, ?, ?, ?, ?)
      on conflict (firebase_uid) do update set
        email=excluded.email,
        first_name=excluded.first_name,
        last_name=excluded.last_name,
        birthday=excluded.birthday,
        address_line1=excluded.address_line1,
        address_line2=excluded.address_line2,
        city=excluded.city,
        state_region=excluded.state_region,
        postal_code=excluded.postal_code,
        country_code=excluded.country_code,
        photo_s3_key=excluded.photo_s3_key,
        photo_url=excluded.photo_url,
        updated_at=now()
      """;

    try (Connection conn = db(); PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, uid);
      ps.setString(2, email);
      ps.setString(3, p.firstName);
      ps.setString(4, p.lastName);
      ps.setDate(5, Date.valueOf(p.birthday));
      ps.setString(6, p.addressLine1);
      ps.setString(7, p.addressLine2);
      ps.setString(8, p.city);
      ps.setString(9, p.stateRegion);
      ps.setString(10, p.postalCode);
      ps.setString(11, p.countryCode);
      ps.setString(12, p.photoS3Key);
      ps.setString(13, p.photoUrl);
      ps.executeUpdate();
    }
  }

  private Connection db() throws SQLException {
    String dbName = System.getenv("DB_NAME");
    String instance = System.getenv("CLOUD_SQL_CONNECTION_NAME");
    String user = System.getenv("DB_USER");
    String pass = System.getenv("DB_PASS");

    if (isBlank(dbName) || isBlank(instance) || isBlank(user) || isBlank(pass)) {
      throw new IllegalStateException("Missing DB env vars (DB_NAME, CLOUD_SQL_CONNECTION_NAME, DB_USER, DB_PASS).");
    }

    String url = String.format(
      "jdbc:postgresql:///%s?cloudSqlInstance=%s&socketFactory=com.google.cloud.sql.postgres.SocketFactory&sslmode=disable",
      dbName,
      instance
    );

    return DriverManager.getConnection(url, user, pass);
  }

  private boolean missingRequired(ProfilePayload p) {
    return isBlank(p.firstName)
      || isBlank(p.lastName)
      || isBlank(p.birthday)
      || isBlank(p.addressLine1)
      || isBlank(p.city)
      || isBlank(p.stateRegion)
      || isBlank(p.postalCode)
      || isBlank(p.countryCode)
      || isBlank(p.photoS3Key)
      || isBlank(p.photoUrl);
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  public static class ProfilePayload {
    public String firstName;
    public String lastName;
    public String birthday;
    public String addressLine1;
    public String addressLine2;
    public String city;
    public String stateRegion;
    public String postalCode;
    public String countryCode;
    public String photoS3Key;
    public String photoUrl;
  }
}
