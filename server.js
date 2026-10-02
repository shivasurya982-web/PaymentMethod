// Run: npm install && node server.js   (Node 18+)
const express = require("express");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const {
  RZP_KEY_ID,
  RZP_KEY_SECRET,
  WEBHOOK_SECRET,
  ADMIN_PASSWORD = "change-me",
  PORT = 3000
} = process.env;

const app = express();
const FILE = path.join(__dirname, "settings.json");

// Default settings
let settings = {
  amount: 499,
  payee: "Your Shop",
  qrImage: "/default-qr.svg",
  upiId: "",
  mode: "manual"
};

try {
  if (fs.existsSync(FILE)) {
    const raw = fs.readFileSync(FILE, "utf-8");
    settings = { ...settings, ...JSON.parse(raw) };
  } else {
    fs.writeFileSync(FILE, JSON.stringify(settings, null, 2));
  }
} catch (e) {
  console.warn("Could not read settings.json, using defaults.", e.message);
}

const orders = new Map(); // In-memory store (or use a DB for production)

const rzp = (apiPath, body) =>
  fetch("https://api.razorpay.com/v1" + apiPath, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization:
        "Basic " +
        Buffer.from(RZP_KEY_ID + ":" + RZP_KEY_SECRET).toString("base64")
    },
    body: JSON.stringify(body || {})
  }).then((r) => r.json());

// 1) Razorpay Webhook (used if Razorpay is configured)
app.post("/webhook", express.raw({ type: "*/*" }), (req, res) => {
  if (!WEBHOOK_SECRET) return res.sendStatus(200);
  const sig = crypto
    .createHmac("sha256", WEBHOOK_SECRET)
    .update(req.body)
    .digest("hex");
  if (sig !== req.headers["x-razorpay-signature"]) return res.sendStatus(400);

  try {
    const e = JSON.parse(req.body);
    if (e.event === "qr_code.credited") {
      const o = orders.get(e.payload.qr_code.entity.id);
      const paid = e.payload.payment.entity.amount; // paise
      if (o && o.status === "PENDING") {
        o.paidAmount = paid;
        o.status = paid === Math.round(o.amount * 100) ? "SUCCESS" : "WRONG";
      }
    }
  } catch (err) {}
  res.sendStatus(200);
});

// Parse JSON with larger limit to allow direct image upload
app.use(express.json({ limit: "10mb" }));
app.use(express.static(path.join(__dirname, "public")));
app.use(express.static(__dirname));

// Public config endpoint
app.get("/api/config", (req, res) => {
  res.json({
    amount: settings.amount,
    payee: settings.payee,
    qrImage: settings.qrImage || "/default-qr.svg",
    upiId: settings.upiId || "",
    mode: settings.mode || "manual"
  });
});

// 2) Admin endpoint: change amount, payee, UPI ID, and QR image
app.post("/api/admin", (req, res) => {
  const { password, amount, payee, upiId, qrImageUrl, qrImageBase64, mode } = req.body;

  if (password !== ADMIN_PASSWORD) {
    return res.status(401).json({ error: "Invalid admin password" });
  }

  const numAmount = Number(amount);
  if (amount !== undefined && (isNaN(numAmount) || numAmount <= 0)) {
    return res.status(400).json({ error: "Amount must be a positive number" });
  }

  if (!isNaN(numAmount) && numAmount > 0) {
    settings.amount = numAmount;
  }

  if (typeof payee === "string" && payee.trim()) {
    settings.payee = payee.trim();
  }

  if (typeof upiId === "string") {
    settings.upiId = upiId.trim();
  }

  if (mode === "manual" || mode === "razorpay") {
    settings.mode = mode;
  }

  // Handle uploaded QR image base64 file
  if (qrImageBase64 && typeof qrImageBase64 === "string") {
    try {
      const match = qrImageBase64.match(/^data:image\/([a-zA-Z0-9+]+);base64,(.+)$/);
      if (match) {
        const rawExt = match[1].toLowerCase();
        const ext = rawExt.includes("jpeg") || rawExt.includes("jpg") ? "jpg" : (rawExt.includes("svg") ? "svg" : "png");
        const filename = `custom-qr.${ext}`;
        const targetPath = path.join(__dirname, "public", filename);
        fs.writeFileSync(targetPath, Buffer.from(match[2], "base64"));
        settings.qrImage = `/${filename}?v=${Date.now()}`;
      }
    } catch (err) {
      return res.status(500).json({ error: "Failed to save uploaded QR image" });
    }
  } else if (qrImageUrl && typeof qrImageUrl === "string" && qrImageUrl.trim()) {
    settings.qrImage = qrImageUrl.trim();
  }

  try {
    fs.writeFileSync(FILE, JSON.stringify(settings, null, 2));
  } catch (err) {
    return res.status(500).json({ error: "Failed to persist settings file" });
  }

  res.json({ success: true, settings });
});

// 3) Create Order (Manual mode or Razorpay fallback)
app.post("/api/order", async (req, res) => {
  const closeBy = Math.floor(Date.now() / 1000) + 600; // 10 minutes

  // If Razorpay keys exist and mode is razorpay, try dynamic QR
  if (RZP_KEY_ID && RZP_KEY_SECRET && settings.mode === "razorpay") {
    try {
      const q = await rzp("/payments/qr_codes", {
        type: "upi_qr",
        name: settings.payee,
        usage: "single_use",
        fixed_amount: false,
        description: "Payment of Rs " + settings.amount,
        close_by: closeBy
      });

      if (q.id) {
        let upi = (q.image_content || "").startsWith("upi://") ? q.image_content : null;
        if (upi && !/[?&]am=/.test(upi)) {
          upi += "&am=" + settings.amount.toFixed(2) + "&cu=INR";
        }
        orders.set(q.id, {
          orderId: q.id,
          amount: settings.amount,
          status: "PENDING",
          expiresAt: closeBy * 1000,
          mode: "razorpay"
        });
        return res.json({
          orderId: q.id,
          amount: settings.amount,
          qrImage: q.image_url,
          upi,
          expiresAt: closeBy * 1000,
          mode: "razorpay"
        });
      }
    } catch (e) {
      console.warn("Razorpay QR creation failed, falling back to manual QR:", e.message);
    }
  }

  // Manual Mode: use custom QR image and optional UPI deep-link
  const orderId = "manual_" + Math.random().toString(36).substring(2, 9) + Date.now().toString(36);
  let upi = null;
  if (settings.upiId) {
    upi = `upi://pay?pa=${encodeURIComponent(settings.upiId)}&pn=${encodeURIComponent(settings.payee)}&am=${settings.amount.toFixed(2)}&cu=INR`;
  }

  orders.set(orderId, {
    orderId,
    amount: settings.amount,
    status: "PENDING",
    expiresAt: closeBy * 1000,
    mode: "manual"
  });

  res.json({
    orderId,
    amount: settings.amount,
    payee: settings.payee,
    qrImage: settings.qrImage || "/default-qr.svg",
    upi,
    upiId: settings.upiId || "",
    expiresAt: closeBy * 1000,
    mode: "manual"
  });
});

// 4) Check payment status
app.get("/api/status", (req, res) => {
  const o = orders.get(req.query.order);
  if (!o) return res.status(404).json({ status: "UNKNOWN" });
  if (o.status === "PENDING" && Date.now() > o.expiresAt) {
    o.status = "CANCELLED";
  }
  res.json({
    status: o.status,
    amount: o.amount,
    paidAmount: (o.paidAmount || 0) / 100,
    utr: o.utr || null,
    mode: o.mode
  });
});

// 5) Manual payment confirmation by customer (or admin)
app.post("/api/confirm", (req, res) => {
  const { order: orderId, utr } = req.body;
  const o = orders.get(orderId);
  if (!o) return res.status(404).json({ error: "Order not found" });

  o.status = "SUCCESS";
  o.paidAmount = Math.round(o.amount * 100);
  o.utr = utr ? String(utr).trim() : "Direct Payment";
  res.json({ success: true, status: "SUCCESS", amount: o.amount, utr: o.utr });
});

// 6) Cancel payment
app.post("/api/cancel", async (req, res) => {
  const o = orders.get(req.body.order);
  if (o && o.status === "PENDING") {
    o.status = "CANCELLED";
    if (RZP_KEY_ID && RZP_KEY_SECRET && o.mode === "razorpay") {
      try {
        rzp(`/payments/qr_codes/${req.body.order}/close`);
      } catch (err) {}
    }
  }
  res.json({ status: o ? o.status : "UNKNOWN" });
});

app.listen(PORT, () => console.log("Running on http://localhost:" + PORT));
