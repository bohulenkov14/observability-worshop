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

// Helper function to generate random transaction data with extended currency support
function generateTransaction(userId) {
    const currencies = ['USD', 'EUR', 'GBP', 'JPY', 'CAD', 'AUD', 'CHF', 'CNY'];
    const currency = currencies[Math.floor(Math.random() * currencies.length)];
    return {
        userId: userId,
        amount: Math.floor(Math.random() * 100) + 1,
        description: `Test transaction at ${new Date().toISOString()}`,
        currency: currency
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
        userId = responseBody.data.id; // Assuming the response includes the UUID in data.id
        console.log(`Created user with ID: ${userId}`);
    } catch (e) {
        console.error('Failed to parse user creation response:', e);
        return;
    }

    sleep(1);

    // 2. Top up the user's account
    const topUpData = {
        userId: userId, // Use the UUID from user creation
        amount: Math.floor(Math.random() * 1000) + 100
    };
    const topUpRes = http.post(`${BASE_URL}/user/topup`, JSON.stringify(topUpData), {
        headers: { 'Content-Type': 'application/json' },
    });
    check(topUpRes, { 'top-up successful': (r) => r.status === 200 });

    sleep(1);

    // 3. Create a transaction
    const txData = generateTransaction(userId);
    const createTxRes = http.post(`${BASE_URL}/transaction/create`, JSON.stringify(txData), {
        headers: { 'Content-Type': 'application/json' },
    });
    check(createTxRes, { 'transaction created': (r) => r.status === 201 });

    sleep(1);

    // 4. Get user transactions
    const getTxRes = http.get(`${BASE_URL}/transaction/user/${userId}`, {
        headers: { 'Content-Type': 'application/json' },
    });
    check(getTxRes, { 'get transactions successful': (r) => r.status === 200 });

    // Wait between 2-5 seconds before next iteration
    sleep(Math.random() * 3 + 2);
}