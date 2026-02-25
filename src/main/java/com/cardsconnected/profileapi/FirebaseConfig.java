package com.cardsconnected.profileapi;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class FirebaseConfig {
  @PostConstruct
  public void init() throws Exception {
    if (!FirebaseApp.getApps().isEmpty()) {
      return;
    }

    String projectId = firstNonBlank(
      System.getenv("FIREBASE_PROJECT_ID"),
      System.getenv("GOOGLE_CLOUD_PROJECT"),
      System.getenv("GCLOUD_PROJECT")
    );

    FirebaseOptions.Builder builder = FirebaseOptions.builder()
      .setCredentials(GoogleCredentials.getApplicationDefault());

    if (!isBlank(projectId)) {
      builder.setProjectId(projectId);
    }

    String serviceAccountId = System.getenv("FIREBASE_SERVICE_ACCOUNT_ID");
    if (!isBlank(serviceAccountId)) {
      builder.setServiceAccountId(serviceAccountId);
    }

    FirebaseApp.initializeApp(builder.build());
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (!isBlank(value)) {
        return value;
      }
    }
    return null;
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
