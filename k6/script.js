import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';

export const options = {
    vus: 2, // Number of virtual users
    duration: '24h', // Run continuously
};

const BASE_URL = 'http://public-api:8080';

// Create a shared array to store our 10 fixed users
const users = new SharedArray('users', function() {
    const users = [];
    for (let i = 0; i < 10; i++) {
        users.push({
            username: `fixed_user_${i}`,
            email: `fixed_user_${i}@example.com`
        });
    }
    return users;
});

// Store created user IDs
let createdUserIds = new Set();

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

// Setup function to create our fixed set of users
export function setup() {
    const userIds = [];

    // Only create users if they don't exist
    for (const userData of users) {
        const createUserRes = http.post(`${BASE_URL}/user/create`, JSON.stringify(userData), {
            headers: { 'Content-Type': 'application/json' },
        });

        if (createUserRes.status === 201) {
            try {
                const responseBody = JSON.parse(createUserRes.body);
                userIds.push(responseBody.data.id);
                console.log(`Created user with ID: ${responseBody.data.id}`);
            } catch (e) {
                console.error('Failed to parse user creation response:', e);
            }
        }
        sleep(1);
    }

    return { userIds: userIds };
}

export default function(data) {
    // Get a random user ID from our fixed set
    const userId = data.userIds[Math.floor(Math.random() * data.userIds.length)];

    // 1. Top up the user's account
    const topUpData = {
        amount: generateAmount(),
        currency: generateCurrency()
    };
    const topUpRes = http.post(`${BASE_URL}/user/${userId}/top-up`, JSON.stringify(topUpData), {
        headers: { 'Content-Type': 'application/json' },
    });
    check(topUpRes, { 'top-up successful': (r) => r.status === 201 });

    sleep(1);

    // 2. Make a purchase
    const purchaseData = generatePurchase();
    const purchaseRes = http.post(`${BASE_URL}/user/${userId}/purchase`, JSON.stringify(purchaseData), {
        headers: { 'Content-Type': 'application/json' },
    });
    check(purchaseRes, { 'purchase successful': (r) => r.status === 201 });

    sleep(1);

    // 3. Get user transactions
    const getTxRes = http.get(`${BASE_URL}/user/${userId}/transactions`, {
        headers: { 'Content-Type': 'application/json' },
    });
    check(getTxRes, { 'get transactions successful': (r) => r.status === 200 });

    // Wait between 2-5 seconds before next iteration
    sleep(Math.random() * 3 + 2);
}