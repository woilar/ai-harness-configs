import { Rpc } from '@opencode/plugin/rpc';
const object=properties=>({type:'object',properties,required:Object.keys(properties),additionalProperties:false});
const string={type:'string'};
export const ReviewRpc=Rpc.define({id:'opencode-review',events:{},methods:{
  ping:{input:object({}),output:object({version:string}),errors:{}},
  prepare:{input:object({sessionID:string,turns:{type:'array',maxItems:100,items:object({threadID:string,id:string,text:string})}}),output:object({ok:{type:'boolean'}}),errors:{}},
  read:{input:object({threads:{type:'array',maxItems:30,items:object({id:string,sessionID:string})}}),output:object({threads:{type:'array',items:{type:'object',additionalProperties:true}}}),errors:{}},
}});
