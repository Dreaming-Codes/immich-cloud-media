package codes.dreaming.cloudmedia.provider;

import android.os.Bundle;
import android.provider.CloudMediaProvider;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// Android 13 calls onGetMediaCollectionInfo with null extras, although the API marks them @NonNull.
// Kotlin rejects a nullable override, and its generated null check throws, so this override is in Java.
public abstract class CloudMediaProviderCompat extends CloudMediaProvider {

  @NonNull
  @Override
  public final Bundle onGetMediaCollectionInfo(@Nullable Bundle extras) {
    return getMediaCollectionInfo();
  }

  @NonNull
  protected abstract Bundle getMediaCollectionInfo();
}
