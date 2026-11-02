/* Darvi Billing v0.3 historical vault. Separate browser IndexedDB from live v0.2 billing store.
   No cross-company records and no historical sale can enter active invoicing without a future reconciliation workflow. */
(function(global){
'use strict';
const DB_NAME='darvi_billing_legacy_v03', TYPES=['customers','items','invoices','payments','purchases','expenses','reports'];
const ALIASES={
 customers:{id:['id','party id','party code','customer id','customer code'],name:['name','party name','customer name','client name','company name','firm name'],phone:['phone','mobile','mobile number','contact no','contact number','phone no'],email:['email','email id'],gstin:['gstin','gst no','gst number','gst number (if available)'],address:['address','billing address','party address'],openingBalance:['opening balance','balance','closing balance','receivable','amount due']},
 items:{id:['id','item code','item id','sku','barcode','product code'],name:['name','item','item name','product','product name','description'],category:['category','group','item group'],unit:['unit','unit name','uom'],hsn:['hsn','hsn code','hsn/sac','hsn code/sac'],price:['sale price','selling price','sales price','price','rate','sale rate'],purchasePrice:['purchase price','cost price','purchase rate'],tax:['tax','gst','gst %','tax rate','gst rate'],stock:['stock','quantity','closing stock','opening stock','current stock']},
 invoices:{id:['invoice no','invoice number','bill no','bill number','voucher number','voucher no','sale no','sales no','reference number'],name:['party name','customer name','customer','party','buyer'],date:['invoice date','date','bill date','sale date'],total:['total','total amount','grand total','bill amount','invoice amount','sale amount'],paid:['paid','paid amount','received amount','amount received'],balance:['balance','balance due','pending amount','due'],gst:['gst','tax amount','gst amount'],item:['item','item name','product','product name'],quantity:['qty','quantity','sold qty']},
 payments:{id:['receipt no','receipt number','payment id','transaction id','reference','reference no'],name:['customer','party name','customer name','party'],date:['date','payment date','receipt date'],amount:['amount','paid amount','received amount','receipt amount'],invoice:['invoice no','invoice number','bill no','bill number'],method:['mode','payment mode','payment method','method']},
 purchases:{id:['purchase no','purchase number','bill no','bill number','voucher no','invoice no'],name:['supplier','supplier name','party name','vendor','vendor name'],date:['date','bill date','purchase date'],total:['total','total amount','bill amount','grand total'],paid:['paid','paid amount'],item:['item name','item','product']},
 expenses:{id:['expense id','voucher no','reference','bill no'],name:['expense','expense name','category','particulars','payee'],date:['date','expense date'],total:['amount','total','total amount'],method:['method','payment mode','mode']},
 reports:{id:['report id','voucher no','invoice no','reference'],name:['report','report name','particulars','description','item name','party name'],date:['date','from date','transaction date'],total:['amount','total','total amount','sales amount','debit','credit']}
};
function norm(s){return String(s??'').toLowerCase().trim().replace(/[_.\-\t]/g,' ').replace(/[^a-z0-9%/ ]/g,'').replace(/\s+/g,' ');}
function parseCSV(source){let input=String(source).replace(/^\uFEFF/,'');let out=[],row=[],cell='',quoted=false;
 for(let i=0;i<input.length;i++){const ch=input[i];if(quoted){if(ch==='"'&&input[i+1]==='"'){cell+='"';i++;}else if(ch==='"')quoted=false;else cell+=ch;}
 else if(ch==='"')quoted=true;else if(ch===','){row.push(cell);cell='';}else if(ch==='\r'||ch==='\n'){if(ch==='\r'&&input[i+1]==='\n')i++;row.push(cell);if(row.some(x=>x.trim()!==''))out.push(row);row=[];cell='';}else cell+=ch;}
 if(quoted)throw Error('CSV has an unclosed quoted cell.');row.push(cell);if(row.some(x=>x.trim()!==''))out.push(row);
 if(!out.length)return {columns:[],rows:[]};const headers=out.shift().map((s,i)=>String(s).trim()||`Column ${i+1}`);
 return {columns:headers,rows:out.map(vals=>Object.fromEntries(headers.map((h,i)=>[h,String(vals[i]??'')])))};}
function mapped(type,raw){const src=Object.entries(raw),lookup=new Map(src.map(([k,v])=>[norm(k),v]));const m={};for(const [k,names] of Object.entries(ALIASES[type]||{})){for(const label of names){const v=lookup.get(norm(label));if(v!==undefined&&String(v).trim()){m[k]=String(v).trim();break;}}}return m;}
function amount(v){return Number(String(v??'').replace(/[₹,\s]/g,'').replace(/^\((.*)\)$/,'-$1'))||0;}
function simpleHash(s){let h=2166136261;for(let i=0;i<s.length;i++){h^=s.charCodeAt(i);h=Math.imul(h,16777619);}return (h>>>0).toString(36);}
function identity(type,m,raw,i,source){let name=norm(m.name||'');let id=norm(m.id||'');
 if(type==='customers')return id||norm(m.phone)||norm(m.gstin)||name||simpleHash(JSON.stringify(raw));
 if(type==='items')return id||name||simpleHash(JSON.stringify(raw));
 if(type==='invoices')return (id||simpleHash(JSON.stringify(raw)))+'|'+norm(m.item||'')+'|'+norm(m.quantity||'');
 if(type==='payments')return id||[norm(m.invoice),norm(m.date),norm(m.amount),name].join('|')||simpleHash(JSON.stringify(raw));
 if(type==='purchases'||type==='expenses')return id||[norm(m.date),name,norm(m.total)].join('|');
 return norm(source)+'|'+simpleHash(JSON.stringify(raw))+'|'+i;
}
function typeFromName(name){const s=String(name).toLowerCase();if(/custom|parties|party|client|ledger/.test(s))return 'customers';if(/stock|product|items/.test(s))return 'items';if(/purchase/.test(s))return 'purchases';if(/payment|receipt/.test(s))return 'payments';if(/expense/.test(s))return 'expenses';if(/invoice|sales|billing|transaction/.test(s))return 'invoices';return 'reports';}
function parseJSON(content,type,businessId){let d=JSON.parse(content),entries=[];
 if(d?.exportFormat==='darvi-legacy-archive'&&Array.isArray(d.records)){return d.records.map(r=>({type:TYPES.includes(r.type)?r.type:type,raw:r.raw||r.mapped||r,source:r.source||'',originBusiness:r.businessId||''}));}
 if(d?.exportFormat==='darvi-billing-backup')d=d.data;
 if(d?.businesses){d=d.businesses[businessId]||d.businesses[Object.keys(d.businesses)[0]]||d;}
 const sections=[['customers','customers'],['items','products'],['items','items'],['invoices','invoices'],['payments','payments'],['purchases','purchases'],['expenses','expenses'],['reports','reports']];
 if(Array.isArray(d))entries=d.map(raw=>({type,raw}));
 else {for(const [t,k] of sections)if(Array.isArray(d[k]))entries.push(...d[k].map(raw=>({type:t,raw})));}
 if(!entries.length)throw Error('No supported records found in JSON. Export CSV/Excel from the old app; encrypted proprietary backups cannot be imported here.');
 return entries;
}
async function parseFile(file,type,businessId){if(!TYPES.includes(type))throw Error('Select a valid record type.');if(file.size>20*1024*1024)throw Error('Files over 20 MB need batching. Split the export into smaller CSV files.');
 const text=await file.text();let rows=[];
 if(/\.json$/i.test(file.name)){rows=parseJSON(text,type,businessId);}else if(/\.csv$/i.test(file.name)||/\.txt$/i.test(file.name)){const data=parseCSV(text);if(!data.columns.length)throw Error('CSV file has no header row.');rows=data.rows.map(raw=>({type,raw}));}else throw Error('Please use CSV or JSON. Export Excel sheets as CSV UTF-8 first.');
 if(!rows.length)throw Error('No data rows were found.');
 const prepped=rows.map((r,i)=>{const t=TYPES.includes(r.type)?r.type:type,raw=r.raw&&typeof r.raw==='object'&&!Array.isArray(r.raw)?r.raw:{Value:String(r.raw??'')};const m=mapped(t,raw);return {type:t,raw,mapped:m,identity:identity(t,m,raw,i,r.source||file.name),source:r.source||file.name,originBusiness:r.originBusiness||'',rowNumber:i+2};});
 const counts={};for(const r of prepped)counts[r.type]=(counts[r.type]||0)+1;
 return {fileName:file.name,size:file.size,rows:prepped,counts,preview:prepped.slice(0,5).map(r=>({type:r.type,...r.mapped}))};
}
function openDb(){return new Promise((resolve,reject)=>{if(!global.indexedDB){reject(Error('Historical archive requires IndexedDB; use Chrome or Edge with browser storage enabled.'));return;}const req=indexedDB.open(DB_NAME,1);req.onupgradeneeded=()=>{const d=req.result;const r=d.createObjectStore('records',{keyPath:'key'});r.createIndex('byBusiness','businessId',{unique:false});r.createIndex('byTypeBusiness',['businessId','type'],{unique:false});r.createIndex('byBatch','batchId',{unique:false});d.createObjectStore('batches',{keyPath:'id'}).createIndex('byBusiness','businessId',{unique:false});};req.onerror=()=>reject(req.error||Error('Could not open archive'));req.onsuccess=()=>resolve(req.result);});}
async function transact(mode,stores,fn){const d=await openDb();return new Promise((resolve,reject)=>{let value;const t=d.transaction(stores,mode);t.oncomplete=()=>{d.close();resolve(value);};t.onerror=()=>{d.close();reject(t.error||Error('Archive transaction failed'));};t.onabort=()=>{d.close();reject(t.error||Error('Archive transaction aborted'));};try{value=fn(t,(x)=>{value=x;});}catch(e){t.abort();reject(e);}});}
async function importRows(businessId,stage,sourceName){if(!businessId||!stage?.rows?.length)throw Error('No records selected.');const batchId='mig_'+Date.now().toString(36)+'_'+Math.random().toString(36).slice(2,8),now=new Date().toISOString();
 return transact('readwrite',['records','batches'],(tx,setResult)=>{const records=tx.objectStore('records'),batches=tx.objectStore('batches');const result={batchId,added:0,duplicates:0,counts:{}};let remaining=stage.rows.length;
 for(const r of stage.rows){if(r.originBusiness&&r.originBusiness!==businessId){tx.abort();throw Error('Archive belongs to a different business. Please switch businesses.');}
 const key=[businessId,r.type,r.identity].join('|');const req=records.get(key);
 req.onsuccess=()=>{if(req.result){result.duplicates++;}else{records.put({key,businessId,type:r.type,raw:r.raw,mapped:r.mapped,source:sourceName||stage.fileName,rowNumber:r.rowNumber,batchId,importedAt:now});result.added++;result.counts[r.type]=(result.counts[r.type]||0)+1;}
 if(!--remaining){batches.put({id:batchId,businessId,sourceName:sourceName||stage.fileName,originalFile:stage.fileName,importedAt:now,added:result.added,duplicates:result.duplicates,counts:result.counts});setResult(result);}};
 }
 });}
async function getArchive(businessId){const db=await openDb();return new Promise((resolve,reject)=>{const tx=db.transaction(['records','batches'],'readonly');const r=tx.objectStore('records').index('byBusiness').getAll(businessId),b=tx.objectStore('batches').index('byBusiness').getAll(businessId);let results={records:[],batches:[]};r.onsuccess=()=>{results.records=r.result;};b.onsuccess=()=>{results.batches=b.result;};tx.oncomplete=()=>{db.close();resolve(results);};tx.onerror=()=>{db.close();reject(tx.error||Error('Cannot load archive'));};});}
function templateCSV(type){const headers={customers:['Customer ID','Customer Name','Mobile','Email','GSTIN','Address','Opening Balance'],items:['Item Code','Item Name','Category','Unit','HSN','Sale Price','Purchase Price','GST %','Closing Stock'],invoices:['Invoice No','Invoice Date','Customer Name','Item Name','Qty','Total Amount','Paid Amount','Balance'],payments:['Receipt No','Date','Customer Name','Invoice No','Amount','Payment Mode'],purchases:['Purchase No','Date','Supplier Name','Item Name','Total Amount'],expenses:['Expense ID','Date','Category','Amount','Payment Mode'],reports:['Report Name','Date','Description','Total Amount']}[type];return '\uFEFF'+headers.map(x=>'"'+x+'"').join(',')+'\r\n';}
function projectMasters(rows,business){const r={customers:0,items:0,skipped:0};for(const entry of rows){const m=entry.mapped;
 if(entry.type==='customers'){
 if(!m.name){r.skipped++;continue;}const exists=business.customers.some(c=>(m.phone&&c.phone&&norm(c.phone)===norm(m.phone))||(m.gstin&&c.gstin&&norm(c.gstin)===norm(m.gstin))||norm(c.name)===norm(m.name));if(exists){r.skipped++;continue;}business.customers.push({id:'legacy_customer_'+simpleHash(entry.identity),name:m.name,phone:m.phone||'',email:m.email||'',gstin:m.gstin||'',address:m.address||'',legacyOpeningBalance:m.openingBalance||'',historicalReference:true});r.customers++;
 }else if(entry.type==='items'){
 if(!m.name){r.skipped++;continue;}const exists=business.products.some(p=>norm(p.name)===norm(m.name));if(exists){r.skipped++;continue;}business.products.push({id:'legacy_item_'+simpleHash(entry.identity),name:m.name,category:m.category||'Imported',type:'item',unit:m.unit||'unit',hsn:m.hsn||'',price:amount(m.price),purchasePrice:amount(m.purchasePrice),tax:Math.min(100,Math.max(0,amount(m.tax))),stock:0,legacyOpeningStock:m.stock||'',lowStock:0,active:true,historicalReference:true});r.items++;
 }}return r;}
global.DarviLegacy={DB_NAME,TYPES,typeFromName,parseCSV,parseFile,importRows,getArchive,templateCSV,projectMasters,amount};
})(window);
