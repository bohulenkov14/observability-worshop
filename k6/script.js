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
            email: `fixed_user_${i}@example.com`,
            externalId: `ext_id_${i}`
        });
    }
    return users;
});

// Store created user IDs
let createdUserIds = new Set();

// Currency configuration with approximate exchange rates and ranges
const CURRENCY_CONFIG = {
    USD: {
        min: 500,
        max: 1500,
        avg: 1000, // Direct USD amount
    },
    EUR: {
        min: 450, // ~500 USD at 1.1 rate
        max: 1350, // ~1500 USD at 1.1 rate
        avg: 900, // ~1000 USD at 1.1 rate
    },
    GBP: {
        min: 380, // ~500 USD at 1.3 rate
        max: 1150, // ~1500 USD at 1.3 rate
        avg: 770, // ~1000 USD at 1.3 rate
    },
    JPY: {
        min: 75757, // ~500 USD at 0.0066 rate
        max: 227272, // ~1500 USD at 0.0066 rate
        avg: 151515, // ~1000 USD at 0.0066 rate
    }
};

// Helper function to generate random amount based on currency
function generateAmount(currency) {
    const config = CURRENCY_CONFIG[currency];

    // Generate a random amount within ±30% of the average
    const variation = config.avg * 0.3; // 30% variation
    const min = Math.max(config.min, config.avg - variation);
    const max = Math.min(config.max, config.avg + variation);

    // Generate random amount within the range
    let amount;
    if (currency === 'JPY') {
        // For JPY, round to nearest 100 yen since decimals aren't used
        amount = Math.round((min + Math.random() * (max - min)) / 100) * 100;
    } else {
        // For other currencies, round to 2 decimal places
        amount = Math.round(min + Math.random() * (max - min));
    }

    return amount;
}

// Helper function to generate random currency with weighted probabilities
function generateCurrency() {
    // Weight currencies to make USD more common
    const weights = {
        USD: 0.4, // 40% chance
        EUR: 0.3, // 30% chance
        GBP: 0.2, // 20% chance
        JPY: 0.1 // 10% chance
    };

    const random = Math.random();
    let cumulativeWeight = 0;

    for (const [currency, weight] of Object.entries(weights)) {
        cumulativeWeight += weight;
        if (random <= cumulativeWeight) {
            return currency;
        }
    }

    return 'USD'; // fallback
}

// Helper function to generate random purchase data
function generatePurchase() {
    const currency = generateCurrency();
    return {
        amount: generateAmount(currency),
        description: `Purchase at ${new Date().toISOString()}`,
        currency: currency
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

    // 1. Top up the user's account (always in USD)
    const topUpData = {
        amount: generateAmount('USD'), // Always use USD for top-ups
        currency: 'USD'
    };
    const topUpRes = http.post(`${BASE_URL}/user/${userId}/top-up`, JSON.stringify(topUpData), {
        headers: { 'Content-Type': 'application/json' },
    });
    check(topUpRes, { 'top-up successful': (r) => r.status === 201 });

    sleep(1);

    // 2. Make a purchase (can be in any currency)
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