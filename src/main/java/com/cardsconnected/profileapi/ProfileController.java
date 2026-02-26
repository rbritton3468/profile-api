package com.cardsconnected.profileapi;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/profile")
public class ProfileController {
  private static final List<String> REQUIRED_FIELDS = List.of(
    "firstName",
    "lastName",
    "birthday",
    "addressLine1",
    "city",
    "stateRegion",
    "postalCode",
    "countryCode"
  );

  @GetMapping("/health")
  public Map<String, Object> health() {
    return Map.of("ok", true);
  }

  @GetMapping("/db-test")
  public ResponseEntity<Map<String, Object>> dbTest() {
    String sql = "select 1 as ok";
    try (Connection conn = db();
         PreparedStatement ps = conn.prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {
      if (rs.next()) {
        return ResponseEntity.ok(Map.of("ok", rs.getInt("ok") == 1));
      }
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "No rows returned"));
    } catch (SQLException sqlException) {
      return databaseError(sqlException);
    } catch (IllegalStateException stateException) {
      return configError(stateException);
    }
  }

  @GetMapping("/missing-fields")
  public ResponseEntity<Map<String, Object>> missingFields(
    @RequestHeader(value = "Authorization", required = false) String authHeader
  ) {
    FirebaseToken decoded;
    try {
      decoded = verifyBearer(authHeader);
    } catch (UnauthorizedException unauthorizedException) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", unauthorizedException.getMessage()));
    }

    String sql = """
      select first_name,last_name,birthday,address_line1,city,state_region,postal_code,country_code,photo_url
      from users
      where firebase_uid=?
      """;

    try (Connection conn = db(); PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, decoded.getUid());

      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return ResponseEntity.ok(Map.of(
            "ok", true,
            "exists", false,
            "profileComplete", false,
            "missingRequired", REQUIRED_FIELDS,
            "missingOptional", List.of("photo")
          ));
        }

        List<String> missingRequired = new ArrayList<>();
        List<String> missingOptional = new ArrayList<>();

        if (isBlank(rs.getString("first_name"))) missingRequired.add("firstName");
        if (isBlank(rs.getString("last_name"))) missingRequired.add("lastName");
        if (rs.getDate("birthday") == null) missingRequired.add("birthday");
        if (isBlank(rs.getString("address_line1"))) missingRequired.add("addressLine1");
        if (isBlank(rs.getString("city"))) missingRequired.add("city");
        if (isBlank(rs.getString("state_region"))) missingRequired.add("stateRegion");
        if (isBlank(rs.getString("postal_code"))) missingRequired.add("postalCode");
        if (isBlank(rs.getString("country_code"))) missingRequired.add("countryCode");
        if (isBlank(rs.getString("photo_url"))) missingOptional.add("photo");

        return ResponseEntity.ok(Map.of(
          "ok", true,
          "exists", true,
          "profileComplete", missingRequired.isEmpty(),
          "missingRequired", missingRequired,
          "missingOptional", missingOptional
        ));
      }
    } catch (SQLException sqlException) {
      return databaseError(sqlException);
    } catch (IllegalStateException stateException) {
      return configError(stateException);
    }
  }

  @GetMapping("/me")
  public ResponseEntity<Map<String, Object>> me(
    @RequestHeader(value = "Authorization", required = false) String authHeader
  ) {
    FirebaseToken decoded;
    try {
      decoded = verifyBearer(authHeader);
    } catch (UnauthorizedException unauthorizedException) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", unauthorizedException.getMessage()));
    }

    String sql = """
      select email,first_name,last_name,birthday,address_line1,address_line2,city,state_region,postal_code,country_code,photo_url
      from users
      where firebase_uid=?
      """;

    try (Connection conn = db(); PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, decoded.getUid());

      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return ResponseEntity.ok(Map.of("ok", true, "exists", false));
        }

        Map<String, Object> profile = new HashMap<>();
        Date birthday = rs.getDate("birthday");
        profile.put("email", rs.getString("email"));
        profile.put("firstName", rs.getString("first_name"));
        profile.put("lastName", rs.getString("last_name"));
        profile.put("birthday", birthday == null ? null : birthday.toString());
        profile.put("addressLine1", rs.getString("address_line1"));
        profile.put("addressLine2", rs.getString("address_line2"));
        profile.put("city", rs.getString("city"));
        profile.put("stateRegion", rs.getString("state_region"));
        profile.put("postalCode", rs.getString("postal_code"));
        profile.put("countryCode", rs.getString("country_code"));
        profile.put("photoUrl", rs.getString("photo_url"));

        return ResponseEntity.ok(Map.of("ok", true, "exists", true, "profile", profile));
      }
    } catch (SQLException sqlException) {
      return databaseError(sqlException);
    } catch (IllegalStateException stateException) {
      return configError(stateException);
    }
  }

  @PatchMapping("/name")
  public ResponseEntity<Map<String, Object>> updateName(
    @RequestHeader(value = "Authorization", required = false) String authHeader,
    @RequestBody NamePayload payload
  ) {
    FirebaseToken decoded;
    try {
      decoded = verifyBearer(authHeader);
    } catch (UnauthorizedException unauthorizedException) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", unauthorizedException.getMessage()));
    }

    if (isBlank(payload.firstName) || isBlank(payload.lastName)) {
      return ResponseEntity.badRequest().body(Map.of("error", "firstName and lastName are required"));
    }

    String sql = """
      update users
      set first_name=?, last_name=?, updated_at=now()
      where firebase_uid=?
      """;

    try (Connection conn = db(); PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, payload.firstName.trim());
      ps.setString(2, payload.lastName.trim());
      ps.setString(3, decoded.getUid());

      int updated = ps.executeUpdate();
      if (updated == 0) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(Map.of("error", "Profile row not found. Create profile first with /profile/upsert."));
      }

      return ResponseEntity.ok(Map.of("ok", true));
    } catch (SQLException sqlException) {
      return databaseError(sqlException);
    } catch (IllegalStateException stateException) {
      return configError(stateException);
    }
  }

  @PatchMapping("/address")
  public ResponseEntity<Map<String, Object>> updateAddress(
    @RequestHeader(value = "Authorization", required = false) String authHeader,
    @RequestBody AddressPayload payload
  ) {
    FirebaseToken decoded;
    try {
      decoded = verifyBearer(authHeader);
    } catch (UnauthorizedException unauthorizedException) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", unauthorizedException.getMessage()));
    }

    if (isBlank(payload.addressLine1)
      || isBlank(payload.city)
      || isBlank(payload.stateRegion)
      || isBlank(payload.postalCode)
      || isBlank(payload.countryCode)) {
      return ResponseEntity.badRequest()
        .body(Map.of("error", "addressLine1, city, stateRegion, postalCode, and countryCode are required"));
    }

    String sql = """
      update users
      set address_line1=?, address_line2=?, city=?, state_region=?, postal_code=?, country_code=?, updated_at=now()
      where firebase_uid=?
      """;

    try (Connection conn = db(); PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, payload.addressLine1.trim());
      ps.setString(2, isBlank(payload.addressLine2) ? null : payload.addressLine2.trim());
      ps.setString(3, payload.city.trim());
      ps.setString(4, payload.stateRegion.trim());
      ps.setString(5, payload.postalCode.trim());
      ps.setString(6, payload.countryCode.trim().toUpperCase());
      ps.setString(7, decoded.getUid());

      int updated = ps.executeUpdate();
      if (updated == 0) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(Map.of("error", "Profile row not found. Create profile first with /profile/upsert."));
      }

      return ResponseEntity.ok(Map.of("ok", true));
    } catch (SQLException sqlException) {
      return databaseError(sqlException);
    } catch (IllegalStateException stateException) {
      return configError(stateException);
    }
  }

  @PatchMapping("/photo")
  public ResponseEntity<Map<String, Object>> updatePhoto(
    @RequestHeader(value = "Authorization", required = false) String authHeader,
    @RequestBody PhotoPayload payload
  ) {
    FirebaseToken decoded;
    try {
      decoded = verifyBearer(authHeader);
    } catch (UnauthorizedException unauthorizedException) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", unauthorizedException.getMessage()));
    }

    if (isBlank(payload.photoUrl)) {
      return ResponseEntity.badRequest().body(Map.of("error", "photoUrl is required"));
    }

    String sql = """
      update users
      set photo_s3_key=?, photo_url=?, updated_at=now()
      where firebase_uid=?
      """;

    try (Connection conn = db(); PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, isBlank(payload.photoS3Key) ? "users/" + decoded.getUid() + "/profile/unknown" : payload.photoS3Key.trim());
      ps.setString(2, payload.photoUrl.trim());
      ps.setString(3, decoded.getUid());

      int updated = ps.executeUpdate();
      if (updated == 0) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(Map.of("error", "Profile row not found. Create profile first with /profile/upsert."));
      }

      return ResponseEntity.ok(Map.of("ok", true));
    } catch (SQLException sqlException) {
      return databaseError(sqlException);
    } catch (IllegalStateException stateException) {
      return configError(stateException);
    }
  }

  @PostMapping("/upsert")
  public ResponseEntity<Map<String, Object>> upsert(
    @RequestHeader(value = "Authorization", required = false) String authHeader,
    @RequestBody ProfilePayload payload
  ) {
    FirebaseToken decoded;
    try {
      decoded = verifyBearer(authHeader);
    } catch (UnauthorizedException unauthorizedException) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", unauthorizedException.getMessage()));
    }

    try {
      if (missingRequired(payload)) {
        return ResponseEntity.badRequest().body(Map.of("error", "Missing required profile fields"));
      }

      upsertUser(decoded.getUid(), decoded.getEmail(), payload);
      return ResponseEntity.ok(Map.of("ok", true));
    } catch (SQLException sqlException) {
      return databaseError(sqlException);
    } catch (IllegalStateException stateException) {
      return configError(stateException);
    }
  }

  private FirebaseToken verifyBearer(String authHeader) throws UnauthorizedException {
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      throw new UnauthorizedException("Missing bearer token");
    }

    try {
      return FirebaseAuth.getInstance().verifyIdToken(authHeader.substring(7));
    } catch (Exception ex) {
      throw new UnauthorizedException("Invalid Firebase ID token");
    }
  }

  private void upsertUser(String uid, String email, ProfilePayload p) throws SQLException {
    String photoS3Key = isBlank(p.photoS3Key) ? "users/" + uid + "/profile/none" : p.photoS3Key;
    String photoUrl = isBlank(p.photoUrl) ? "" : p.photoUrl;

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
      ps.setString(3, p.firstName.trim());
      ps.setString(4, p.lastName.trim());
      ps.setDate(5, Date.valueOf(p.birthday));
      ps.setString(6, p.addressLine1.trim());
      ps.setString(7, isBlank(p.addressLine2) ? null : p.addressLine2.trim());
      ps.setString(8, p.city.trim());
      ps.setString(9, p.stateRegion.trim());
      ps.setString(10, p.postalCode.trim());
      ps.setString(11, p.countryCode.trim().toUpperCase());
      ps.setString(12, photoS3Key);
      ps.setString(13, photoUrl);
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
      || isBlank(p.countryCode);
  }

  private ResponseEntity<Map<String, Object>> databaseError(SQLException sqlException) {
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
  }

  private ResponseEntity<Map<String, Object>> configError(IllegalStateException stateException) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
      .body(Map.of("error", "Config error: " + stateException.getMessage()));
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

  public static class NamePayload {
    public String firstName;
    public String lastName;
  }

  public static class AddressPayload {
    public String addressLine1;
    public String addressLine2;
    public String city;
    public String stateRegion;
    public String postalCode;
    public String countryCode;
  }

  public static class PhotoPayload {
    public String photoS3Key;
    public String photoUrl;
  }

  private static class UnauthorizedException extends Exception {
    UnauthorizedException(String message) {
      super(message);
    }
  }
}
