// Export only. cases/runs JSON remains authoritative; no Android dependencies.
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {createRequire} from 'node:module';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
// A local ignored junction points to the workspace loader's bundled node_modules.
const require=createRequire(path.join(root,'.tools/test-library/runtime.cjs'));
const {Workbook,SpreadsheetFile}=await import(pathToFileURL(require.resolve('@oai/artifact-tool')).href);
const data=JSON.parse(await fs.readFile(path.join(root,'dist/testing/report-data.json'),'utf8'));
const output=path.join(root,'dist/testing/outputs/20261030-test-library');
await fs.mkdir(output,{recursive:true});
const book=Workbook.create();
const widths={
  '测试用例库':[26,14,40,14,8,14,15,15,17,48,52,68,74,22,22,24,64,16,28],
  '测试执行记录':[38,28,40,26,35,13,12,14,10,20,16,55,80,46,68,68,32,48,48,72,35,45,18]
};
function col(index){let s='';for(let n=index+1;n;n=Math.floor((n-1)/26))s=String.fromCharCode(65+(n-1)%26)+s;return s;}
for(const [name,matrix] of Object.entries(data.sheets)){
  const sheet=book.worksheets.add(name);sheet.showGridLines=false;sheet.tabColor=name==='测试用例库'?'#406655':'#7C9585';
  const last=col(matrix[0].length-1),end=matrix.length+4;
  sheet.getRange(`A1:${last}${end}`).format.font={name:'Arial',size:10,color:'#202A24'};
  sheet.getRange('A2').values=[[name==='测试用例库'?'Floating Focus 测试用例库':'Floating Focus 测试执行记录']];
  sheet.getRange('A2').format.font={name:'Arial',size:14,bold:true,color:'#244334'};
  sheet.getRange('A3').values=[['导出报告。维护源：docs/testing；按ID、环境、身份和复测筛选，不在Excel双份维护。']];
  sheet.getRange('A4').values=[[`目标 ${data.target.id}；本表 ${matrix.length-1} 条。历史/不同粒度数量不代表当前功能覆盖率。`]];
  sheet.getRange('A3:A4').format.font={name:'Arial',size:10,color:'#5B655E'};
  sheet.getRange(`A5:${last}${end}`).values=matrix.map(row=>row.map(v=>typeof v==='string' && /^[=+@]/.test(v)?"'"+v:v));
  const table=sheet.tables.add(`A5:${last}${end}`,true,name==='测试用例库'?'CaseLibrary':'ExecutionRecords');
  table.style='TableStyleMedium4';table.showFilterButton=true;table.showBandedColumns=false;
  sheet.getRange(`A5:${last}5`).format={fill:'#406655',font:{name:'Arial',size:10,bold:true,color:'#FFFFFF'},wrapText:true,horizontalAlignment:'center',verticalAlignment:'center',rowHeight:32};
  sheet.getRange(`A6:${last}${end}`).format.wrapText=true;
  sheet.getRange(`A6:${last}${end}`).format.verticalAlignment='top';
  widths[name].forEach((w,i)=>sheet.getRange(`${col(i)}1:${col(i)}${end}`).format.columnWidth=w);
  sheet.getRange(`A6:${last}${end}`).format.autofitRows();
  sheet.freezePanes.freezeRows(5);sheet.freezePanes.freezeColumns(2);
  const states=(name==='测试用例库'?['当前自动结果','当前真机结果']:['结果']).map(label=>col(matrix[0].indexOf(label)));
  for(const c of states){
    const r=sheet.getRange(`${c}6:${c}${end}`);
    r.conditionalFormats.add('containsText',{text:'失败',format:{fill:'#FBE4E0',font:{color:'#9B2823',bold:true}}});
    r.conditionalFormats.add('containsText',{text:'阻塞',format:{fill:'#FFF0D2',font:{color:'#7D541A'}}});
  }
}
book.recalculate();
for(const name of Object.keys(data.sheets)){
  const view=await book.inspect({kind:'table',range:`'${name}'!A5:I9`,include:'values',tableMaxRows:5,tableMaxCols:9,maxChars:2200});
  console.log(view.ndjson);
  const preview=await book.render({sheetName:name,range:'A1:I10',scale:1,format:'png'});
  await fs.writeFile(path.join(output,name+'.png'),new Uint8Array(await preview.arrayBuffer()));
}
for(const [sheetName,range,label] of [['测试用例库','K5:M9','用例步骤'],['测试执行记录','J5:M9','结果身份']]){
  const preview=await book.render({sheetName,range,scale:1,format:'png'});
  await fs.writeFile(path.join(output,label+'.png'),new Uint8Array(await preview.arrayBuffer()));
}
const errors=await book.inspect({kind:'match',searchTerm:'#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#SPILL!|#CALC!',options:{useRegex:true,maxResults:30},maxChars:1000});
console.log(errors.ndjson);
await (await SpreadsheetFile.exportXlsx(book)).save(path.join(output,'FloatingFocus-tests.xlsx'));
console.log('Exported',path.join(output,'FloatingFocus-tests.xlsx'));
