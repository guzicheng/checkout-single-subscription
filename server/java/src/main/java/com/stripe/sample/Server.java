package com.stripe.sample;

import java.nio.file.Paths;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static spark.Spark.get;
import static spark.Spark.post;
import static spark.Spark.port;
import static spark.Spark.staticFiles;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;

import com.stripe.Stripe;
import com.stripe.model.Event;
import com.stripe.exception.*;
import com.stripe.model.LineItemCollection;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItemCollection;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.model.checkout.Session;
import com.stripe.param.SubscriptionRetrieveParams;
import com.stripe.param.checkout.SessionCreateParams;

import io.github.cdimascio.dotenv.Dotenv;

public class Server {
    private static Gson gson = new Gson();

    public static void main(String[] args) {
        port(4242);

        Dotenv dotenv = Dotenv.load();

        Stripe.apiKey = dotenv.get("STRIPE_SECRET_KEY");
        // For sample support and debugging, not required for production:
        Stripe.setAppInfo(
            "stripe-samples/checkout-single-subscription",
            "0.0.2",
            "https://github.com/stripe-samples/checkout-single-subscription"
        );


        staticFiles.externalLocation(
                Paths.get(Paths.get("").toAbsolutePath().toString(), dotenv.get("STATIC_DIR")).normalize().toString());

        get("/config", (request, response) -> {
            response.type("application/json");

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("publishableKey", dotenv.get("STRIPE_PUBLISHABLE_KEY"));
            responseData.put("basicPrice", dotenv.get("BASIC_PRICE_ID"));
            responseData.put("proPrice", dotenv.get("PRO_PRICE_ID"));
            return gson.toJson(responseData);
        });

        // Fetch the Checkout Session to display the JSON result on the success page
        get("/checkout-session", (request, response) -> {
            response.type("application/json");

            String sessionId = request.queryParams("sessionId");
            Session session = Session.retrieve(sessionId);
//            System.out.println("Session Retrieved: " + session);

            return gson.toJson(session);
        });

        post("/create-checkout-session", (request, response) -> {
            String domainUrl = dotenv.get("DOMAIN");

            // Create new Checkout Session for the order
            // Other optional params include:
            // [billing_address_collection] - to display billing address details on the page
            // [customer] - if you have an existing Stripe Customer ID
            // [payment_intent_data] - lets capture the payment later
            // [customer_email] - lets you prefill the email input in the form
            // [automatic_tax] - to automatically calculate sales tax, VAT and GST in the checkout page
            // For full details see https://stripe.com/docs/api/checkout/sessions/create

            // ?session_id={CHECKOUT_SESSION_ID} means the redirect will have the session ID
            // set as a query param
            SessionCreateParams params = new SessionCreateParams.Builder()
                    .setSuccessUrl(domainUrl + "/success.html?session_id={CHECKOUT_SESSION_ID}")
                    .setCancelUrl(domainUrl + "/canceled.html")
                    .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                    .setCustomer("cus_SOsDxxP9s6hez1")
                    .addLineItem(new SessionCreateParams.LineItem.Builder()
                      .setQuantity(1L)
                      .setPrice(request.queryParams("priceId"))
                      .build()
                    )
                    .setSubscriptionData(SessionCreateParams.SubscriptionData.builder()
                            .setTrialPeriodDays(7L) // 设置试用期为7天
//                            .setTrialEnd(1610403705L) // 设置试用期结束时间（Unix时间戳）
                            .build())
                    // 启用自动税费计算，需要在Stripe 后台设置商户 Origin Address
//                    .setAutomaticTax(SessionCreateParams.AutomaticTax.builder().setEnabled(true).build())
                    .addExpand("subscription") // 关键：展开订阅信息
                    .addExpand("customer") // 关键：展开客户信息
                    .addExpand("line_items.data.price") // 关键：展开订阅项中关联的 price
                    .build();

            try {
                Session session = Session.create(params);
//                System.out.println("Session Created: " + session);
                System.out.println("Session Created: ");
                printCheckoutSession(session);

                response.redirect(session.getUrl(), 303);
                return "";
            } catch(Exception e) {
                Map<String, Object> messageData = new HashMap<>();
                messageData.put("message", e.getMessage());
                Map<String, Object> responseData = new HashMap<>();
                responseData.put("error", messageData);
                response.status(400);
                return gson.toJson(responseData);
            }
        });

        post("/customer-portal", (request, response) -> {
            // For demonstration purposes, we're using the Checkout session to retrieve the customer ID.
            // Typically this is stored alongside the authenticated user in your database.
            Session checkoutSession = Session.retrieve(request.queryParams("sessionId"));
            String customer = checkoutSession.getCustomer();
            String domainUrl = dotenv.get("DOMAIN");

            com.stripe.param.billingportal.SessionCreateParams params = new com.stripe.param.billingportal.SessionCreateParams.Builder()
                .setReturnUrl(domainUrl)
                .setCustomer(customer)
                .build();
            com.stripe.model.billingportal.Session portalSession = com.stripe.model.billingportal.Session.create(params);

            response.redirect(portalSession.getUrl(), 303);
            return "";
        });

        post("/webhook", (request, response) -> {
            String payload = request.body();
            String sigHeader = request.headers("Stripe-Signature");
            String endpointSecret = dotenv.get("STRIPE_WEBHOOK_SECRET");

            Event event = null;

            try {
                    event = Webhook.constructEvent(payload, sigHeader, endpointSecret);
            } catch (SignatureVerificationException e) {
                // Invalid signature
                response.status(400);
                return "";
            }

//            System.out.println(String.format("Webhook received [%s]: %s", event.getType(), event.getData().toString()));

            switch (event.getType()) {
                case "checkout.session.completed":
                    System.out.println("Payment succeeded!");
                    printSubscriptionFromEvent(event);

                    response.status(200);
                    return "";
                default:
                    response.status(200);
                    return "";
            }
        });
    }


    private static void printCheckoutSession(Session session) {
        if (session == null) {
            System.out.println("Session is null!");
            return;
        }

        String subscriptionId = Optional.ofNullable(session).map(Session::getSubscription).orElse("");
        String customerId = Optional.ofNullable(session).map(Session::getCustomer).orElse("");
        System.out.println(" -- Subscription ID: " + subscriptionId);
        System.out.println(" -- Customer ID: " + customerId);

        Optional.ofNullable(session).map(Session::getLineItems)
                .map(LineItemCollection::getData)
                .ifPresent(lineItems -> {

                    lineItems.forEach(lineItem -> {
                        String priceId = Optional.ofNullable(lineItem.getPrice()).map(price -> price.getId()).orElse("");
                        System.out.println(" -- Line Item Price ID: " + priceId);
                    });
        });
    }

    private static void printSubscriptionFromEvent(Event event) {
        String json = event.getDataObjectDeserializer().getRawJson();
        JSONObject jsonObject = JSON.parseObject(json);

        String subscriptionId = jsonObject.getString("subscription");
        String customerId = jsonObject.getString("customer");
        System.out.println("Subscription Data from Event: ");
        System.out.println(" -- Subscription ID: " + customerId);
        System.out.println(" -- Customer ID: " + customerId);

        try {
            if (subscriptionId != null) {
                Subscription subscription = Subscription.retrieve(subscriptionId,
                        SubscriptionRetrieveParams.builder()
                                .addExpand("customer")
                                .addExpand("items.data.price")
                                .build(),
                        RequestOptions.getDefault());
                System.out.println("Subscription retrieved: ");
                printSubscription(subscription);
            }

        } catch (StripeException e) {
            System.out.println("Subscription retrieve Error: " + e.getMessage());
        }
    }

    private static void printSubscription(Subscription subscription) {
        if (subscription == null) {
            System.out.println("Subscription is null!");
            return;
        }

        String customerId = Optional.ofNullable(subscription).map(Subscription::getCustomer).orElse("");
        System.out.println(" -- Customer ID: " + customerId);

        Optional.ofNullable(subscription).map(Subscription::getItems)
                .map(SubscriptionItemCollection::getData)
                .ifPresent(lineItems -> {
                    lineItems.forEach(item -> {
                        String priceId = Optional.ofNullable(item.getPrice()).map(price -> price.getId()).orElse("");
                        System.out.println(" -- Subscription Item Price ID: " + priceId);
                    });
                });
    }

}
