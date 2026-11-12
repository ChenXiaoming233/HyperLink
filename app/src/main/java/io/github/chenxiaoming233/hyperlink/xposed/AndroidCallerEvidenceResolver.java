package io.github.chenxiaoming233.hyperlink.xposed;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import io.github.chenxiaoming233.hyperlink.rpc.CallerEvidence;
import io.github.chenxiaoming233.hyperlink.rpc.CallerEvidenceResolver;
import io.github.chenxiaoming233.hyperlink.rpc.CallerIdentity;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class AndroidCallerEvidenceResolver implements CallerEvidenceResolver {
    private final Context context;

    AndroidCallerEvidenceResolver(Context context) {
        this.context = context;
    }

    @Override
    public CallerEvidence resolve(CallerIdentity identity) {
        PackageManager packageManager = context.getPackageManager();
        String[] packages = packageManager.getPackagesForUid(identity.uid());
        if (packages == null || packages.length == 0) {
            return new CallerEvidence(
                    identity.uid(), Collections.emptyList(), Collections.emptySet());
        }

        Set<String> signerHashes = new LinkedHashSet<>();
        for (String packageName : packages) {
            collectSignerHashes(packageManager, packageName, signerHashes);
        }
        return new CallerEvidence(identity.uid(), Arrays.asList(packages), signerHashes);
    }

    private void collectSignerHashes(
            PackageManager packageManager, String packageName, Set<String> destination) {
        try {
            PackageInfo packageInfo =
                    packageManager.getPackageInfo(
                            packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo signingInfo = packageInfo.signingInfo;
            if (signingInfo == null) {
                return;
            }
            Signature[] signers = signingInfo.getApkContentsSigners();
            if (signers == null) {
                return;
            }
            for (Signature signer : signers) {
                destination.add(sha256(signer.toByteArray()));
            }
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException ignored) {
            // Authentication remains fail-closed when a caller disappears or hashing fails.
        }
    }

    private String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder value = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            value.append(String.format(Locale.ROOT, "%02x", item));
        }
        return value.toString();
    }
}
