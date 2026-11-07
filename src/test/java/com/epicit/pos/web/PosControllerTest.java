package com.epicit.pos.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epicit.pos.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class PosControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void posScreenRendersSeededMenuWithThousandSeparatedPrices() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Meat Kofta")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2,500 RWF")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("+ Onion")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("CUSTOMER COPY")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("KITCHEN TICKET")));
    }

    @Test
    void pingIdentifiesThisApplication() throws Exception {
        mvc.perform(get("/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("epicit-pos"));
    }
}
