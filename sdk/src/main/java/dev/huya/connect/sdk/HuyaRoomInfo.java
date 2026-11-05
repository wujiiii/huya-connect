package dev.huya.connect.sdk;

import java.util.List;
import java.util.Map;

/** Immutable latest observations; inspect each result's outcome and connection before using it. */
public record HuyaRoomInfo(HuyaQueryResult<Long> fanClubMembers, HuyaQueryResult<Long> guardianCount,
                           HuyaQueryResult<Long> followerCount, HuyaQueryResult<LiveInfo> liveStatus,
                           HuyaQueryResult<GiftCatalog> giftCatalog) {
    public enum LiveState { LIVE, OFFLINE, UNKNOWN }
    /** Protocol start time is epoch seconds. Zero and absent fields are retained distinctly. */
    public record LiveInfo(LiveState state, Boolean replay, String liveId, Long startTimeEpochSeconds) { }
    public record GiftItem(String giftId, Integer iPropsType, String name, Long priceRaw,
                           List<Jce.Field> fields) {
        public GiftItem { fields = List.copyOf(fields); }
    }
    /** Full gift records from getResourceInfo; collection time/hash/raw response are on the enclosing result. */
    public record GiftCatalog(Map<String, GiftItem> gifts, String resourceVersion) {
        public GiftCatalog { gifts = Map.copyOf(gifts); }
    }
}
