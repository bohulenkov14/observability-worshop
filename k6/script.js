import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 2, // Number of virtual users
    duration: '24h', // Run continuously
};

const BASE_URL = 'http://public-api:8080';

// Helper function to generate random user data
function generateUser() {
    const id = Math.floor(Math.random() * 1000000);
    return {
        username: `user_${id}`,
        email: `user_${id}@example.com`
    };
}

// Helper function to generate random amount
function generateAmount() {
    return Math.floor(Math.random() * 1000) + 100;
}

// Helper function to generate random currency
function generateCurrency() {
    const currencies = ['USD', 'EUR', 'GBP', 'JPY', 'CAD', 'AUD', 'CHF', 'CNY'];
    return currencies[Math.floor(Math.random() * currencies.length)];
}

// Helper function to generate random purchase data
function generatePurchase() {
    return {
        amount: generateAmount(),
        description: `Purchase at ${new Date().toISOString()}`,
        currency: generateCurrency()
    };
}

export default function() {
    // 1. Create a user
    const userData = generateUser();
    const createUserRes = http.post(`${BASE_URL}/user/create`, JSON.stringify(userData), {
        headers: { 'Content-Type': 'application/json' },
    });
    check(createUserRes, { 'user created': (r) => r.status === 201 });

    // Extract the user ID from the response
    let userId;
    try {
        const responseBody = JSON.parse(createUserRes.body);
        userId = responseBody.data.id;
        console.log(`Created user with ID: ${userId}`);
    } catch (e) {
        console.error('Failed to parse user creation response:', e);
        return;
    }

    sleep(1);

    // 2. Top up the user's account
    const topUpData = {
        amount: generateAmount(),
        currency: generateCurrency()
    };
    const topUpRes = http.post(`${BASE_URL}/user/${userId}/top-up`, JSON.stringify(topUpData), {
        headers: { 'Content-Type': 'application/json' },
    });
    check(topUpRes, { 'top-up successful': (r) => r.status === 201 });

    sleep(1);

    // 3. Make a purchase
    const purchaseData = generatePurchase();
    const purchaseRes = http.post(`${BASE_URL}/user/${userId}/purchase`, JSON.stringify(purchaseData), {
        headers: { 'Content-Type': 'application/json' },
    });
    check(purchaseRes, { 'purchase successful': (r) => r.status === 201 });

    sleep(1);

    // 4. Get user transactions
    const getTxRes = http.get(`${BASE_URL}/user/${userId}/transactions`, {
        headers: { 'Content-Type': 'application/json' },
    });
    check(getTxRes, { 'get transactions successful': (r) => r.status === 200 });

    // Wait between 2-5 seconds before next iteration
    sleep(Math.random() * 3 + 2);
}