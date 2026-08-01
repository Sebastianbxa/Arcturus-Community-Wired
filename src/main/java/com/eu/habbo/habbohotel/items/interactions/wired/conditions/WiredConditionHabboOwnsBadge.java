package com.eu.habbo.habbohotel.items.interactions.wired.conditions;

import com.eu.habbo.habbohotel.items.Item;
import com.eu.habbo.habbohotel.items.interactions.InteractionWiredCondition;
import com.eu.habbo.habbohotel.items.interactions.wired.WiredSettings;
import com.eu.habbo.habbohotel.rooms.Room;
import com.eu.habbo.habbohotel.rooms.RoomUnit;
import com.eu.habbo.habbohotel.users.Habbo;
import com.eu.habbo.habbohotel.wired.WiredConditionOperator;
import com.eu.habbo.habbohotel.wired.WiredConditionType;
import com.eu.habbo.habbohotel.wired.core.WiredContext;
import com.eu.habbo.habbohotel.wired.core.WiredManager;
import com.eu.habbo.habbohotel.wired.core.WiredSources;
import com.eu.habbo.habbohotel.wired.core.WiredTriggerSourceResolver;
import com.eu.habbo.messages.ServerMessage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public class WiredConditionHabboOwnsBadge extends InteractionWiredCondition {

    public static final WiredConditionType type = WiredConditionType.ACTOR_WEARS_BADGE;

    protected static final int QUANTIFIER_ALL = 0;
    protected static final int QUANTIFIER_ANY = 1;

    protected String badgeCode = "";
    protected int quantifier   = QUANTIFIER_ALL;
    protected int userSource   = WiredSources.SOURCE_TRIGGER;

    public WiredConditionHabboOwnsBadge(ResultSet set, Item baseItem) throws SQLException {
        super(set, baseItem);
    }

    public WiredConditionHabboOwnsBadge(int id, int userId, Item item, String extradata, int limitedStack, int limitedSells) {
        super(id, userId, item, extradata, limitedStack, limitedSells);
    }

    @Override
    public boolean evaluate(WiredContext ctx) {
        return matchesCondition(ctx);
    }

    protected boolean matchesCondition(WiredContext ctx) {
        if (this.badgeCode == null || this.badgeCode.isEmpty()) return false;

        Room room = ctx.room();
        List<RoomUnit> users = WiredTriggerSourceResolver.resolveUsers(this, ctx.event(), this.normalizeUserSource(this.userSource), null);
        if (users.isEmpty()) return false;

        if (this.quantifier == QUANTIFIER_ALL) {
            return users.stream().allMatch(u -> hasBadge(u, room));
        }
        return users.stream().anyMatch(u -> hasBadge(u, room));
    }

    private boolean hasBadge(RoomUnit unit, Room room) {
        if (unit == null || room == null) return false;
        Habbo habbo = room.getHabbo(unit);
        return habbo != null && habbo.getInventory().getBadgesComponent().hasBadge(this.badgeCode);
    }

    @Deprecated
    @Override
    public boolean execute(RoomUnit roomUnit, Room room, Object[] stuff) {
        return false;
    }

    @Override
    public WiredConditionType getType() {
        return type;
    }

    @Override
    public WiredConditionOperator operator() {
        return WiredConditionOperator.AND;
    }

    @Override
    public String getWiredData() {
        return WiredManager.getGson().toJson(new JsonData(this.badgeCode, this.quantifier, this.userSource));
    }

    @Override
    public void loadWiredData(ResultSet set, Room room) throws SQLException {
        String wiredData = set.getString("wired_data");
        if (wiredData != null && wiredData.startsWith("{")) {
            JsonData data = WiredManager.getGson().fromJson(wiredData, JsonData.class);
            if (data != null) {
                this.badgeCode  = data.badgeCode != null ? data.badgeCode : "";
                this.quantifier = (data.quantifier == QUANTIFIER_ANY) ? QUANTIFIER_ANY : QUANTIFIER_ALL;
                this.userSource = normalizeUserSource(data.userSource);
            }
        }
    }

    @Override
    public void onPickUp() {
        this.badgeCode  = "";
        this.quantifier = QUANTIFIER_ALL;
        this.userSource = WiredSources.SOURCE_TRIGGER;
    }

    @Override
    public void serializeWiredData(ServerMessage message, Room room) {
        message.appendBoolean(false);
        message.appendInt(0);
        message.appendInt(0);
        message.appendInt(this.getBaseItem().getSpriteId());
        message.appendInt(this.getId());
        message.appendString(this.badgeCode != null ? this.badgeCode : "");
        message.appendInt(2);
        message.appendInt(this.quantifier);
        message.appendInt(this.userSource);
        message.appendInt(0);
        message.appendInt(this.getType().code);
        message.appendInt(0);
        message.appendInt(0);
    }

    @Override
    public boolean saveData(WiredSettings settings) {
        int[] p = settings.getIntParams();
        this.badgeCode  = settings.getStringParam() != null ? settings.getStringParam() : "";
        this.quantifier = (p.length > 0) ? ((p[0] == QUANTIFIER_ANY) ? QUANTIFIER_ANY : QUANTIFIER_ALL) : QUANTIFIER_ALL;
        this.userSource = (p.length > 1) ? normalizeUserSource(p[1])                                     : WiredSources.SOURCE_TRIGGER;
        return true;
    }

    private int normalizeUserSource(int source) {
        return WiredSources.normalizeSource(source, WiredSources.SOURCE_TRIGGER,
                WiredSources.SOURCE_SELECTOR, WiredSources.SOURCE_SIGNAL);
    }

    static class JsonData {
        String badgeCode;
        int quantifier;
        int userSource;

        public JsonData(String badgeCode, int quantifier, int userSource) {
            this.badgeCode  = badgeCode;
            this.quantifier = quantifier;
            this.userSource = userSource;
        }
    }
}
