package com.gustler.devtools;

/** SAL-158: 기존 초기 데이터는 유지하고, 수집 후 재시작과 별도 dev 후보 번들만 허용한 전환. */
final class CompatiblePreparation {
    static final String PREVIOUS = "1760d3f777e91af09ca577ebabb6faa0ab577f79aef92ef2bb8be27750f3fa56";
    static final String NEXT = "52b4d7117c1db439b8e90468eb334fc063f20a12387d4a72495ed936a45a508d";

    static boolean permits(RevisionStore.Revision previous, RevisionStore.Revision next) {
        return PREVIOUS.equals(previous.files().get("tools/data-contract.sha256"))
            && NEXT.equals(next.files().get("tools/data-contract.sha256"));
    }
}
