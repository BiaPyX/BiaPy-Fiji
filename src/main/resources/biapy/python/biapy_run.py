"""
Runs BiaPy with the arguments given (--config, --result_dir, --gpu...). Used as the script that
torch.distributed.run starts once per GPU when a job uses several GPUs.
"""
import biapy

if __name__ == "__main__":
    biapy.main()
