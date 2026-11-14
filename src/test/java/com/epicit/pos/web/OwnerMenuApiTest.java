package com.epicit.pos.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epicit.pos.IntegrationTest;
import com.epicit.pos.domain.MenuItem;
import com.epicit.pos.domain.PaymentMethod;
import com.epicit.pos.repo.MenuItemRepository;
import com.epicit.pos.service.MenuPalette;
import com.epicit.pos.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** FR-6 over HTTP: the contract the Menu screen depends on, plus the audit and history guarantees. */
@IntegrationTest
class OwnerMenuApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MenuItemRepository menuItems;
    @Autowired OrderService orders;

    MockHttpSession owner;
    MenuItem meat;
    MenuItem fateera;

    @BeforeEach
    void setUp() throws Exception {
        owner = new MockHttpSession();
        mvc.perform(post("/api/auth/setup").session(owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"4821\"}")).andExpect(status().isOk());
        meat = menuItems.findByActiveTrueOrderByIdAsc().get(0);
        fateera = menuItems.findByActiveTrueOrderByIdAsc().get(2);
    }

    private ResultActions send(org.springframework.test.web.servlet.RequestBuilder b) throws Exception {
        return mvc.perform(b);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).session(owner).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions putJson(String url, String body) throws Exception {
        return mvc.perform(put(url).session(owner).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long count(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ?", Long.class, action);
    }

    private int stockOf(long id) {
        return jdbc.queryForObject("SELECT stock FROM menu_item WHERE id = ?", Integer.class, id);
    }

    // ---- owner only (NFR-7) -----------------------------------------------------------------------------------

    @Test
    void everyMenuEndpointRefusesACashierWithoutAnOwnerSession() throws Exception {
        String json = MediaType.APPLICATION_JSON_VALUE;
        send(get("/api/owner/menu")).andExpect(status().isUnauthorized());
        send(post("/api/owner/menu/items").contentType(json).content("{\"name\":\"X\",\"price\":1}")).andExpect(status().isUnauthorized());
        send(put("/api/owner/menu/items/" + meat.getId()).contentType(json).content("{\"price\":1}")).andExpect(status().isUnauthorized());
        send(post("/api/owner/menu/items/" + meat.getId() + "/restock").contentType(json).content("{\"delta\":5}")).andExpect(status().isUnauthorized());
        send(post("/api/owner/menu/items/" + meat.getId() + "/active").contentType(json).content("{\"active\":false}")).andExpect(status().isUnauthorized());
        send(post("/api/owner/menu/addons").contentType(json).content("{\"name\":\"X\"}")).andExpect(status().isUnauthorized());
        send(post("/api/owner/menu/addons/1/active").contentType(json).content("{\"active\":false}")).andExpect(status().isUnauthorized());
        assertThat(stockOf(meat.getId())).isEqualTo(100);
    }

    @Test
    void thePosScreenShowsTheOwnerMenuButton() throws Exception {
        send(get("/")).andExpect(content().string(containsString("id=\"menu-btn\"")));
    }

    // ---- reading ----------------------------------------------------------------------------------------------

    @Test
    void theMenuListsEveryItemAndAddonIncludingDeactivatedOnesWithThePaletteAndDefaults() throws Exception {
        postJson("/api/owner/menu/items/" + fateera.getId() + "/active", "{\"active\":false}").andExpect(status().isOk());

        send(get("/api/owner/menu").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[2].name").value("Fateera"))
                .andExpect(jsonPath("$.items[2].active").value(false))
                .andExpect(jsonPath("$.addons", hasSize(10)))
                .andExpect(jsonPath("$.palette", hasSize(13)))
                .andExpect(jsonPath("$.lowStockDefault").value(5));
    }

    // ---- adding (FR-6.1) --------------------------------------------------------------------------------------

    @Test
    void aNewItemGetsTheNextUnusedPaletteColourAndAppearsOnThePosGrid() throws Exception {
        postJson("/api/owner/menu/items", "{\"name\":\"Shawarma\",\"price\":3000,\"stock\":40}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Shawarma"))
                .andExpect(jsonPath("$.price").value(3000))
                .andExpect(jsonPath("$.stock").value(40))
                .andExpect(jsonPath("$.lowStockThreshold").value(5))     // the Settings default
                .andExpect(jsonPath("$.color").value(MenuPalette.COLORS.get(3)))
                .andExpect(jsonPath("$.active").value(true));

        postJson("/api/owner/menu/items", "{\"name\":\"Falafel\",\"price\":1000,\"stock\":10}")
                .andExpect(jsonPath("$.color").value(MenuPalette.COLORS.get(4)));

        send(get("/")).andExpect(content().string(containsString("Shawarma"))).andExpect(content().string(containsString("3,000 RWF")));
        assertThat(count("MENU_CHANGE")).isEqualTo(2);
    }

    @Test
    void deactivatingAnItemFreesItsColourForTheNextNewItem() throws Exception {
        postJson("/api/owner/menu/items/" + meat.getId() + "/active", "{\"active\":false}").andExpect(status().isOk());

        postJson("/api/owner/menu/items", "{\"name\":\"Shawarma\",\"price\":3000,\"stock\":1}")
                .andExpect(jsonPath("$.color").value(meat.getColor()));   // the freed slot is reused, not skipped
    }

    @Test
    void aNewItemDefaultsToZeroStockAndHonoursAChosenPaletteColourAndThreshold() throws Exception {
        postJson("/api/owner/menu/items", "{\"name\":\"Juice\",\"price\":500,\"color\":\"#16A085\",\"lowStockThreshold\":12}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(0))
                .andExpect(jsonPath("$.outOfStock").value(true))
                .andExpect(jsonPath("$.color").value("#16a085"))
                .andExpect(jsonPath("$.lowStockThreshold").value(12));
    }

    @Test
    void invalidNewItemsAreRefusedWithAMessageAndNothingIsSavedOrAudited() throws Exception {
        postJson("/api/owner/menu/items", "{\"name\":\"   \",\"price\":100}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("BAD_NAME"));
        postJson("/api/owner/menu/items", "{\"name\":\"" + "a".repeat(101) + "\",\"price\":100}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("BAD_NAME"));
        postJson("/api/owner/menu/items", "{\"name\":\"Tea\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("BAD_PRICE"));
        postJson("/api/owner/menu/items", "{\"name\":\"Tea\",\"price\":0}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("BAD_PRICE"));
        postJson("/api/owner/menu/items", "{\"name\":\"Tea\",\"price\":-5}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("BAD_PRICE"));
        postJson("/api/owner/menu/items", "{\"name\":\"Tea\",\"price\":100,\"stock\":-1}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("BAD_STOCK"));
        postJson("/api/owner/menu/items", "{\"name\":\"Tea\",\"price\":100,\"lowStockThreshold\":-1}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("BAD_THRESHOLD"));
        postJson("/api/owner/menu/items", "{\"name\":\"Tea\",\"price\":100,\"color\":\"#ffffff\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("BAD_COLOR"));
        postJson("/api/owner/menu/items", "{nope").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        assertThat(menuItems.count()).isEqualTo(3);
        assertThat(count("MENU_CHANGE")).isZero();
    }

    @Test
    void aDuplicateNameAmongActiveItemsIsRefusedWhateverTheCase() throws Exception {
        postJson("/api/owner/menu/items", "{\"name\":\"meat KOFTA\",\"price\":100}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("DUPLICATE_NAME"));
    }

    @Test
    void aDeactivatedItemsNameCanBeReusedButItCannotThenBeReactivatedOverTheNewOne() throws Exception {
        postJson("/api/owner/menu/items/" + fateera.getId() + "/active", "{\"active\":false}").andExpect(status().isOk());
        postJson("/api/owner/menu/items", "{\"name\":\"Fateera\",\"price\":1800,\"stock\":5}").andExpect(status().isOk());

        postJson("/api/owner/menu/items/" + fateera.getId() + "/active", "{\"active\":true}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("DUPLICATE_NAME"));
    }

    // ---- editing and price audit (FR-6.1, NFR-8) ------------------------------------------------------------

    @Test
    void aPriceChangeWritesAPriceChangeAuditRowOnlyAndNeverRewritesPastOrders() throws Exception {
        orders.current();
        orders.addItem(meat.getId());
        orders.setPayment(PaymentMethod.CASH);
        orders.startPrint();
        orders.finalizeOrder(orders.customerCopyPrinted().orderNumber());

        putJson("/api/owner/menu/items/" + meat.getId(), "{\"price\":2800}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.price").value(2800));

        assertThat(count("PRICE_CHANGE")).isEqualTo(1);
        assertThat(count("MENU_CHANGE")).isZero();
        assertThat(jdbc.queryForObject("SELECT old_value FROM audit_log WHERE action = 'PRICE_CHANGE'", String.class)).isEqualTo("2500 RWF");
        assertThat(jdbc.queryForObject("SELECT new_value FROM audit_log WHERE action = 'PRICE_CHANGE'", String.class)).isEqualTo("2800 RWF");
        // history is a snapshot: the sold line still says 2,500
        assertThat(jdbc.queryForObject("SELECT unit_price FROM order_line", Long.class)).isEqualTo(2500L);
        assertThat(jdbc.queryForObject("SELECT item_name FROM order_line", String.class)).isEqualTo("Meat Kofta");
    }

    @Test
    void editingNameColourAndThresholdWritesAMenuChangeRowAndLeavesStockAlone() throws Exception {
        putJson("/api/owner/menu/items/" + meat.getId(), "{\"name\":\"Beef Kofta\",\"color\":\"#8e44ad\",\"lowStockThreshold\":9}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Beef Kofta"))
                .andExpect(jsonPath("$.color").value("#8e44ad"))
                .andExpect(jsonPath("$.lowStockThreshold").value(9))
                .andExpect(jsonPath("$.stock").value(100));

        assertThat(count("MENU_CHANGE")).isEqualTo(1);
        assertThat(count("PRICE_CHANGE")).isZero();
    }

    @Test
    void changingNameAndPriceTogetherWritesBothAuditRows() throws Exception {
        putJson("/api/owner/menu/items/" + meat.getId(), "{\"name\":\"Beef Kofta\",\"price\":2600}").andExpect(status().isOk());

        assertThat(count("PRICE_CHANGE")).isEqualTo(1);
        assertThat(count("MENU_CHANGE")).isEqualTo(1);
    }

    @Test
    void savingWithoutChangingAnythingWritesNoAuditRow() throws Exception {
        putJson("/api/owner/menu/items/" + meat.getId(), "{\"name\":\"Meat Kofta\",\"price\":2500,\"lowStockThreshold\":5}").andExpect(status().isOk());

        assertThat(count("PRICE_CHANGE") + count("MENU_CHANGE")).isZero();
    }

    @Test
    void anEditCannotSneakInANewStockFigure() throws Exception {
        putJson("/api/owner/menu/items/" + meat.getId(), "{\"price\":2500,\"stock\":99999}").andExpect(status().isOk());

        assertThat(stockOf(meat.getId())).isEqualTo(100);
    }

    @Test
    void renamingToAnotherActiveItemsNameIsRefusedAndAnUnknownItemIs404() throws Exception {
        putJson("/api/owner/menu/items/" + meat.getId(), "{\"name\":\"fateera\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("DUPLICATE_NAME"));
        putJson("/api/owner/menu/items/999999", "{\"price\":10}").andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // ---- restock (FR-6.3) -------------------------------------------------------------------------------------

    @Test
    void restockAddsToTheCurrentStockAndIsAudited() throws Exception {
        postJson("/api/owner/menu/items/" + meat.getId() + "/restock", "{\"delta\":20}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.stock").value(120));

        assertThat(stockOf(meat.getId())).isEqualTo(120);
        assertThat(jdbc.queryForObject("SELECT old_value FROM audit_log WHERE action = 'MENU_CHANGE'", String.class)).isEqualTo("stock=100");
        assertThat(jdbc.queryForObject("SELECT new_value FROM audit_log WHERE action = 'MENU_CHANGE'", String.class)).isEqualTo("stock=120");
    }

    @Test
    void aNegativeAdjustmentRemovesStockButNeverBelowZero() throws Exception {
        postJson("/api/owner/menu/items/" + meat.getId() + "/restock", "{\"delta\":-30}").andExpect(jsonPath("$.stock").value(70));

        postJson("/api/owner/menu/items/" + meat.getId() + "/restock", "{\"delta\":-71}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("STOCK_BELOW_ZERO"))
                .andExpect(jsonPath("$.message").value(containsString("Only 70 Meat Kofta in stock")));

        assertThat(stockOf(meat.getId())).isEqualTo(70);
        assertThat(count("MENU_CHANGE")).isEqualTo(1);   // the refused attempt left no audit row
        postJson("/api/owner/menu/items/" + meat.getId() + "/restock", "{\"delta\":-70}").andExpect(jsonPath("$.stock").value(0)).andExpect(jsonPath("$.outOfStock").value(true));
    }

    @Test
    void restockRejectsZeroMissingAndAbsurdQuantities() throws Exception {
        for (String body : new String[] {"{\"delta\":0}", "{}", "{\"delta\":1000001}", "{\"delta\":-1000001}"}) {
            postJson("/api/owner/menu/items/" + meat.getId() + "/restock", body)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("BAD_QUANTITY"));
        }
        postJson("/api/owner/menu/items/999999/restock", "{\"delta\":5}").andExpect(status().isNotFound());
        assertThat(stockOf(meat.getId())).isEqualTo(100);
    }

    @Test
    void restockingMovesTheItemOutOfTheLowStockState() throws Exception {
        postJson("/api/owner/menu/items/" + meat.getId() + "/restock", "{\"delta\":-95}").andExpect(jsonPath("$.lowStock").value(true));

        postJson("/api/owner/menu/items/" + meat.getId() + "/restock", "{\"delta\":50}").andExpect(jsonPath("$.lowStock").value(false)).andExpect(jsonPath("$.stock").value(55));
    }

    // ---- deactivate / reactivate (FR-6.1) -----------------------------------------------------------------------

    @Test
    void aDeactivatedItemLeavesThePosGridCannotBeSoldButIsNeverDeleted() throws Exception {
        postJson("/api/owner/menu/items/" + fateera.getId() + "/active", "{\"active\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));

        send(get("/")).andExpect(content().string(not(containsString("Fateera"))));
        mvc.perform(post("/api/order/items").contentType(MediaType.APPLICATION_JSON).content("{\"menuItemId\":" + fateera.getId() + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("ITEM_INACTIVE"));
        assertThat(menuItems.count()).isEqualTo(3);                 // soft delete only
        assertThat(count("MENU_CHANGE")).isEqualTo(1);
    }

    @Test
    void aDeactivatedItemKeepsItsHistoryAndCanComeBack() throws Exception {
        orders.current();
        orders.addItem(fateera.getId());
        orders.startPrint();
        orders.finalizeOrder(orders.customerCopyPrinted().orderNumber());

        postJson("/api/owner/menu/items/" + fateera.getId() + "/active", "{\"active\":false}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_line WHERE menu_item_id = ?", Long.class, fateera.getId())).isEqualTo(1L);

        postJson("/api/owner/menu/items/" + fateera.getId() + "/active", "{\"active\":true}").andExpect(jsonPath("$.active").value(true));
        send(get("/")).andExpect(content().string(containsString("Fateera")));
    }

    @Test
    void settingTheSameActiveStateAgainChangesNothingAndWritesNoAuditRow() throws Exception {
        postJson("/api/owner/menu/items/" + meat.getId() + "/active", "{\"active\":true}").andExpect(status().isOk());
        postJson("/api/owner/menu/items/" + meat.getId() + "/active", "{}").andExpect(status().isBadRequest());

        assertThat(count("MENU_CHANGE")).isZero();
    }

    // ---- add-ons (FR-6.2) -------------------------------------------------------------------------------------

    @Test
    void anAddonCanBeAddedShowsInTheQuickBarAndCanBeDeactivatedAndReactivated() throws Exception {
        postJson("/api/owner/menu/addons", "{\"name\":\"Garlic\"}").andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        send(get("/")).andExpect(content().string(containsString("+ Garlic")));

        long id = jdbc.queryForObject("SELECT id FROM addon WHERE name = 'Garlic'", Long.class);
        postJson("/api/owner/menu/addons/" + id + "/active", "{\"active\":false}").andExpect(jsonPath("$.active").value(false));
        send(get("/")).andExpect(content().string(not(containsString("+ Garlic"))));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM addon WHERE id = ?", Long.class, id)).isEqualTo(1L);   // never deleted

        postJson("/api/owner/menu/addons/" + id + "/active", "{\"active\":true}").andExpect(jsonPath("$.active").value(true));
        assertThat(count("MENU_CHANGE")).isEqualTo(3);
    }

    @Test
    void addonNamesAreValidatedAndUniqueAmongActiveOnes() throws Exception {
        postJson("/api/owner/menu/addons", "{\"name\":\"  \"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("BAD_NAME"));
        postJson("/api/owner/menu/addons", "{\"name\":\"ONION\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("DUPLICATE_NAME"));
        postJson("/api/owner/menu/addons/999999/active", "{\"active\":false}").andExpect(status().isNotFound());
    }
}
