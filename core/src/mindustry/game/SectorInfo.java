package mindustry.game;

import arc.math.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.ctype.*;
import mindustry.maps.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import mindustry.world.modules.*;

import java.util.*;

import static mindustry.Vars.*;

public class SectorInfo{
    /** average window size in samples */
    private static final int valueWindow = 60;
    /** refresh period of export in ticks */
    private static final float refreshPeriod = 60;

    /** Core input statistics. */
    public ObjectMap<Item, ExportStat> production = new ObjectMap<>();
    /** Export statistics. */
    public ObjectMap<Item, ExportStat> export = new ObjectMap<>();
    /** Items stored in all cores. */
    public ItemSeq coreItems = new ItemSeq();
    /** The best available core type. */
    public Block bestCoreType = Blocks.air;
    /** Max storage capacity. */
    public int storageCapacity = 0;
    /** Whether a core is available here. */
    public boolean hasCore = true;
    /** Sector that was launched from. */
    public @Nullable Sector origin;
    /** Launch destination. */
    public @Nullable Sector destination;
    /** Resources known to occur at this sector. */
    public Seq<UnlockableContent> resources = new Seq<>();

    /** Special variables for simulation. */
    public float sumHealth, sumRps, sumDps, waveHealthBase, waveHealthSlope, waveDpsBase, waveDpsSlope;

    /** Time spent at this sector. Do not use unless you know what you're doing. */
    public transient float internalTimeSpent;

    /** Counter refresh state. */
    private transient Interval time = new Interval();
    /** Core item storage to prevent spoofing. */
    private transient int[] coreItemCounts;

    /** Handles core item changes. */
    public void handleCoreItem(Item item, int amount){
        if(coreItemCounts == null){
            coreItemCounts = new int[content.items().size];
        }
        coreItemCounts[item.id] += amount;
    }

    /** @return the real location items go when launched on this sector */
    public Sector getRealDestination(){
        //on multiplayer the destination is, by default, the first captured sector (basically random)
        return !net.client() || destination != null ? destination : state.rules.sector.planet.sectors.find(Sector::hasBase);
    }

    /** Updates export statistics. */
    public void handleItemExport(ItemStack stack){
        handleItemExport(stack.item, stack.amount);
    }

    /** Updates export statistics. */
    public void handleItemExport(Item item, int amount){
        export.get(item, ExportStat::new).counter += amount;
    }

    /** Subtracts from export statistics. */
    public void handleItemImport(Item item, int amount){
        export.get(item, ExportStat::new).counter -= amount;
    }

    public float getExport(Item item){
        return export.get(item, ExportStat::new).mean;
    }

    /** Prepare data for writing to a save. */
    public void prepare(){
        //update core items
        coreItems.clear();

        CoreBuild entity = state.rules.defaultTeam.core();

        if(entity != null){
            ItemModule items = entity.items;
            for(int i = 0; i < items.length(); i++){
                coreItems.set(content.item(i), items.get(i));
            }
        }

        hasCore = entity != null;
        bestCoreType = !hasCore ? Blocks.air : state.rules.defaultTeam.cores().max(e -> e.block.size).block;
        storageCapacity = entity != null ? entity.storageCapacity : 0;

        //update sector's internal time spent counter
        state.rules.sector.setTimeSpent(internalTimeSpent);
        state.rules.sector.setUnderAttack(state.rules.waves);

        SectorDamage.writeParameters(this);
    }

    /** Update averages of various stats, updates some special sector logic.
     * Called every frame. */
    public void update(){
        //updating in multiplayer as a client doesn't make sense
        if(net.client()) return;

        internalTimeSpent += Time.delta;

        //autorun turns
        if(internalTimeSpent >= turnDuration){
            internalTimeSpent = 0;
            universe.runTurn();
        }

        CoreBuild ent = state.rules.defaultTeam.core();

        //refresh throughput
        if(time.get(refreshPeriod)){

            //refresh export
            export.each((item, stat) -> {
                //initialize stat after loading
                if(!stat.loaded){
                    stat.means.fill(stat.mean);
                    stat.loaded = true;
                }

                //add counter, subtract how many items were taken from the core during this time
                stat.means.add(Math.max(stat.counter, 0));
                stat.counter = 0;
                stat.mean = stat.means.rawMean();
            });

            if(coreItemCounts == null){
                coreItemCounts = new int[content.items().size];
            }

            //refresh core items
            for(Item item : content.items()){
                ExportStat stat = production.get(item, ExportStat::new);
                if(!stat.loaded){
                    stat.means.fill(stat.mean);
                    stat.loaded = true;
                }

                //get item delta
                //TODO is preventing negative production a good idea?
                int delta = Math.max(ent == null ? 0 : coreItemCounts[item.id], 0);

                //store means
                stat.means.add(delta);
                stat.mean = stat.means.rawMean();
            }

            Arrays.fill(coreItemCounts, 0);
        }
    }

    public ObjectFloatMap<Item> exportRates(){
        ObjectFloatMap<Item> map = new ObjectFloatMap<>();
        export.each((item, value) -> map.put(item, value.mean));
        return map;
    }

    public static class ExportStat{
        public transient float counter;
        public transient WindowedMean means = new WindowedMean(valueWindow);
        public transient boolean loaded;

        /** mean in terms of items produced per refresh rate (currently, per second) */
        public float mean;

        public String toString(){
            return mean + "";
        }
    }
}
