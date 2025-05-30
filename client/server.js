const express = require('express');
const { createProxyMiddleware } = require('http-proxy-middleware');
const path = require('path');

const app = express();
const PORT = 8000;  // 前端服务器端口
const API_PORT = 4242;  // API服务器端口

// 静态文件服务
app.use(express.static(__dirname));

// API请求代理
app.use('/api', createProxyMiddleware({
    target: `http://localhost:${API_PORT}`,
    changeOrigin: true,
    pathRewrite: {
        '^/api': '', // 重写路径，去掉/api前缀
    },
}));

// 启动服务器
app.listen(PORT, () => {
    console.log(`Frontend server is running on http://localhost:${PORT}`);
    console.log(`API requests will be proxied to http://localhost:${API_PORT}`);
}); 