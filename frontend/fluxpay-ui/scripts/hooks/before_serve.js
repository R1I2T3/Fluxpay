'use strict';
const http = require('http');
const https = require('https');
module.exports = async function(config) {
  const target = new URL(process.env.API_PROXY || 'http://127.0.0.1:8080');
  config.preMiddleware = [...(config.preMiddleware || []), function(req,res,next) {
    if (!req.url.startsWith('/api/')) return next();
    const transport = target.protocol === 'https:' ? https : http;
    const upstream = transport.request({
      hostname:target.hostname,port:target.port || (target.protocol==='https:'?443:80),
      path:req.url,method:req.method,headers:{...req.headers,host:target.host}
    }, incoming => {res.writeHead(incoming.statusCode,incoming.headers);incoming.pipe(res);});
    upstream.setTimeout(30000,()=>upstream.destroy(new Error('Backend timeout')));
    upstream.on('error',()=>{
      if(!res.headersSent) res.writeHead(502,{'Content-Type':'application/json'});
      res.end(JSON.stringify({code:'BACKEND_UNAVAILABLE',message:'The payment service is unavailable. Start the backend and try again.'}));
    });
    req.pipe(upstream);
  }];
  return config;
};
