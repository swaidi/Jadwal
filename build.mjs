import { build } from "esbuild";
import { cpSync, mkdirSync, rmSync } from "node:fs";
rmSync("www",{recursive:true,force:true}); mkdirSync("www/fonts",{recursive:true});
cpSync("src/index.html","www/index.html"); cpSync("src/style.css","www/style.css"); cpSync("src/fonts","www/fonts",{recursive:true});
await build({entryPoints:["src/app.js"],bundle:true,format:"iife",target:["chrome100"],outfile:"www/app.js",minify:false});
console.log("built www/");
